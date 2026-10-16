/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Color
import android.hardware.input.InputManager
import android.hardware.lights.LightState
import android.hardware.lights.LightsRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import io.wiitar.bridge.BridgeConfig
import io.wiitar.bridge.Codes
import io.wiitar.bridge.EvdevEvent
import io.wiitar.bridge.GuitarBridge
import io.wiitar.bridge.GuitarDevices
import io.wiitar.bridge.GuitarDiscovery
import io.wiitar.bridge.GuitarSource
import io.wiitar.bridge.KernelInputDevice
import io.wiitar.bridge.LatencyStats
import io.wiitar.bridge.LiveState
import io.wiitar.bridge.PhysicalInput
import io.wiitar.bridge.Profiles
import io.wiitar.bridge.Uhid
import io.wiitar.bridge.VirtualGamepad
import io.wiitar.protocol.OutputReports
import io.wiitar.protocol.TouchStrip
import io.wiitar.protocol.WiimotePairing
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku user service (ADB shell uid, no root) that turns every connected
 * Wii guitar into its own virtual gamepad, "Wiitar Guitar 1", "… 2", etc.
 *
 * A discovery loop re-reads /proc/bus/input/devices so guitars can connect
 * and disconnect while it runs. Each guitar's evdev devices are read on their
 * own threads; all state changes happen under [lock].
 *
 * Runs as a daemon so it keeps going while a game is in the foreground. It is
 * stopped by unbinding with remove = true, which calls [destroy]; exiting
 * closes every fd, so the kernel removes the virtual gamepads and releases
 * any grabbed device.
 */
class BridgeService() : IBridgeService.Stub() {
    /** Shizuku prefers this constructor; the context tells us where the native library was extracted. */
    constructor(context: Context) : this() {
        nativeLibraryDir = context.applicationInfo?.nativeLibraryDir
        this.context = context
    }

    private var context: Context? = null

    private var nativeLibraryDir: String? = null
    private val lock = Any()
    private var config = BridgeConfig()
    private var profiles: Map<String, BridgeConfig> = emptyMap()
    private var ledWarning = false
    private var running = false
    private val sessions = LinkedHashMap<String, Session>()
    private val errors = ArrayDeque<String>()
    private val latency = LatencyStats()
    private var monotonicClock = false
    private var captureWaiting = false
    private var captureResult = ""
    private var discoveries = 0

    private fun configFor(id: String) = profiles[id] ?: config

    /** One bridged guitar. */
    private inner class Session(val slot: Int, val devices: GuitarDevices) {
        /** The HID device's sysfs directory, home of its LEDs and battery. */
        val sysfs = File("/sys" + devices.key)

        /** The Wii Remote's Bluetooth address (from its battery's name), or the sysfs key as a fallback. */
        val id: String = sysfs.resolve("power_supply").list()
            ?.firstOrNull { it.startsWith("wiimote_battery_") }
            ?.removePrefix("wiimote_battery_")?.uppercase()
            ?: devices.key
        var config = configFor(id)
        var bridge = GuitarBridge(config.mapping(), config.inputs)
        private var grabbedFd: ParcelFileDescriptor? = null
        var battery = -1
        val fds = mutableListOf<ParcelFileDescriptor>()
        val uhid: RandomAccessFile = RandomAccessFile("/dev/uhid", "rw")

        @Volatile
        var alive = true
        var hidden = false
        var touchSeen = false
        var events = 0L

        init {
            // BUS_USB so Android classes it as an external controller (BUS_VIRTUAL reads as built-in).
            uhid.write(
                Uhid.create2(
                    "$VIRTUAL_NAME $slot", VirtualGamepad.DESCRIPTOR, VirtualGamepad.VENDOR, VirtualGamepad.PRODUCT,
                    bus = Uhid.BUS_USB, uniq = devices.key,
                ),
            )
            uhid.write(Uhid.input2(bridge.report()))
            thread("wiitar-uhid-$slot") {
                // Read (and ignore) the kernel's messages to us so its queue never fills up.
                val buffer = ByteArray(Uhid.EVENT_SIZE)
                while (alive && uhid.read(buffer) > 0) Unit
            }
            read(devices.guitar, devices.source, grab = config.hideGuitar)
            devices.accelerometer?.let { read(it, GuitarSource.ACCELEROMETER, grab = false) }
            setPlayerLeds()
            readBattery()
        }

        /** Lights the Wii Remote's LEDs for this player (1-4 one LED, 5-8 pairs), if sysfs lets us. */
        /** Held open while bridged: closing a lights session hands the LEDs back to Android. */
        private var lightsSession: AutoCloseable? = null

        /**
         * Lights the Wii Remote's LEDs for this player (1-4 one LED, 5-8 pairs). Tries the
         * sysfs LED files first (root-only on the Odin), then Android's input-device
         * lights API with the shell user's permissions.
         */
        fun setPlayerLeds() {
            val mask = OutputReports.playerLedMask(slot - 1)
            val leds = sysfs.resolve("leds").listFiles().orEmpty().sortedBy { it.name }
                .filter { Regex(":p[0-3]$").containsMatchIn(it.name) }
            val viaSysfs = leds.isNotEmpty() && leds.withIndex().all { (i, led) ->
                runCatching { led.resolve("brightness").writeText(if (mask and (1 shl i) != 0) "1" else "0") }.isSuccess
            }
            if (viaSysfs) return
            val error = setLedsWithLightsApi(mask)
            if (error != null && !ledWarning) {
                problem("Can't set player LEDs: $error")
                ledWarning = true
            }
        }

        /** @return null on success, else why not. */
        @SuppressLint("NewApi", "MissingPermission")
        private fun setLedsWithLightsApi(mask: Int): String? {
            if (Build.VERSION.SDK_INT < 31) return "needs Android 12+"
            val input = shellContext()?.getSystemService(InputManager::class.java) ?: return "no input service"
            return try {
                // Android doesn't say which input device belongs to which Wii Remote; match by order.
                val remotes = input.inputDeviceIds.toList().mapNotNull { input.getInputDevice(it) }
                    .filter { it.name == GuitarDiscovery.REMOTE_NAME }.sortedBy { it.id }
                // This session isn't in [sessions] yet while it's being opened; count those before it.
                val index = synchronized(lock) { sessions.values.count { it !== this && it.devices.key < devices.key } }
                val remote = remotes.getOrNull(index) ?: return "Wii Remote not found in Android's device list"
                val lights = remote.lightsManager.lights.sortedBy { it.name }.take(4)
                if (lights.isEmpty()) return "Android reports no lights on the Wii Remote"
                val request = LightsRequest.Builder().apply {
                    lights.forEachIndexed { i, light ->
                        addLight(light, LightState.Builder().setColor(if (mask and (1 shl i) != 0) Color.WHITE else 0).build())
                    }
                }.build()
                val session = remote.lightsManager.openSession()
                session.requestLights(request)
                lightsSession?.close()
                lightsSession = session
                null
            } catch (e: Exception) {
                e.toString()
            }
        }

        fun readBattery() {
            battery = sysfs.resolve("power_supply").listFiles().orEmpty()
                .firstNotNullOfOrNull { runCatching { it.resolve("capacity").readText().trim().toInt() }.getOrNull() }
                ?: -1
        }

        private fun read(device: KernelInputDevice, source: GuitarSource, grab: Boolean) {
            val path = node(device) ?: return
            val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            fds += fd
            if (Evdev.load(nativeLibraryDir) == null) {
                monotonicClock = Evdev.useMonotonicClock(fd.fd) == 0
                if (grab) {
                    val result = Evdev.grab(fd.fd, true)
                    hidden = result == 0
                    if (hidden) grabbedFd = fd
                    if (!hidden) problem("Can't hide $path from games (EVIOCGRAB errno ${-result})")
                }
            } else if (grab) {
                problem("Can't hide $path from games (native library: ${Evdev.load(nativeLibraryDir)})")
            }
            val size = if (System.getProperty("os.arch").orEmpty().contains("64")) EvdevEvent.SIZE_64 else EvdevEvent.SIZE_32
            thread("wiitar-$slot-$source") {
                // Closing the stream closes the fd, which also releases the grab.
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    val buffer = ByteArray(size * 64)
                    while (alive) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        var offset = 0
                        while (offset + size <= n) {
                            handle(this, source, EvdevEvent.decode(buffer, offset, size))
                            offset += size
                        }
                    }
                }
                if (source != GuitarSource.ACCELEROMETER) synchronized(lock) { alive = false }
            }
        }

        fun close() {
            alive = false
            runCatching { lightsSession?.close() }
            // Release the grab now rather than when the blocked reader thread next wakes up,
            // so a reopened session can take it again straight away.
            grabbedFd?.let { runCatching { Evdev.grab(it.fd, false) } }
            try {
                uhid.write(Uhid.destroy())
                uhid.close()
            } catch (e: Exception) {
                // Device already gone.
            }
            fds.forEach { runCatching { it.close() } }
        }
    }

    override fun destroy() {
        synchronized(lock) { sessions.values.forEach { it.close() } }
        exitProcess(0)
    }

    override fun start(config: String?, profiles: String?): String {
        synchronized(lock) {
            this.config = BridgeConfig.decode(config)
            this.profiles = Profiles.decode(profiles)
            if (!running) {
                running = true
                thread("wiitar-discovery") {
                    while (true) {
                        try {
                            discover()
                        } catch (e: Exception) {
                            problem("discovery: $e")
                        }
                        Thread.sleep(DISCOVERY_INTERVAL_MS)
                    }
                }
            }
        }
        discover()
        return status()
    }

    override fun configure(config: String?, profiles: String?) {
        synchronized(lock) {
            this.config = BridgeConfig.decode(config)
            this.profiles = Profiles.decode(profiles)
            for ((key, session) in sessions.entries.toList()) {
                val updated = configFor(session.id)
                if (updated == session.config) continue
                if (updated.hideGuitar != session.config.hideGuitar) {
                    // The grab is taken when the device is opened: reopen this guitar (same slot comes back).
                    session.close()
                    sessions.remove(key)
                } else {
                    // Analog state is rebuilt from the next events; held buttons re-sync on their next change.
                    session.config = updated
                    session.bridge = GuitarBridge(updated.mapping(), updated.inputs)
                }
            }
        }
        if (running) discover()
    }

    private fun discover() {
        val devices = KernelInputDevice.parseAll(File("/proc/bus/input/devices").readText())
        val found = GuitarDiscovery.find(devices) { node(it) != null }
        synchronized(lock) {
            val keys = found.map { it.key }.toSet()
            // Drop guitars that disconnected or whose reader died.
            sessions.entries.removeAll { (key, session) ->
                (key !in keys || !session.alive).also { gone -> if (gone) session.close() }
            }
            if (++discoveries % BATTERY_REFRESH_ROUNDS == 0) sessions.values.forEach { it.readBattery() }
            for (guitar in found) {
                if (guitar.key in sessions) continue
                val slot = (1..MAX_GUITARS).firstOrNull { s -> sessions.values.none { it.slot == s } } ?: break
                try {
                    sessions[guitar.key] = Session(slot, guitar)
                } catch (e: Exception) {
                    problem("couldn't bridge ${guitar.guitar.name} (${guitar.key}): $e")
                }
            }
        }
    }

    private fun handle(session: Session, source: GuitarSource, event: EvdevEvent) {
        synchronized(lock) {
            if (!session.alive) return
            session.events++
            // A touch strip shows itself by leaving its idle value (0x0F on the kernel device, 0 on the Odin's copy).
            if (event.type == EvdevEvent.EV_ABS && event.code == Codes.ABS_HAT0X && source != GuitarSource.ACCELEROMETER) {
                val idle = if (source == GuitarSource.KERNEL_GUITAR) TouchStrip.NOT_TOUCHED else 0
                if (event.value != idle) session.touchSeen = true
            }
            if (captureWaiting) {
                PhysicalInput.fromPress(source, event)?.let {
                    captureWaiting = false
                    captureResult = "${source.name}|${it.encode()}|${session.slot}"
                }
            }
            val report = session.bridge.onEvent(source, event) ?: return
            try {
                session.uhid.write(Uhid.input2(report))
                // Only button/strum frames, not the accelerometer's constant stream, measure what players feel.
                if (monotonicClock && source != GuitarSource.ACCELEROMETER) {
                    latency.add(System.nanoTime() / 1000 - event.timeMicros)
                }
            } catch (e: Exception) {
                problem("uhid write failed (guitar ${session.slot}): $e")
                session.alive = false
            }
        }
    }

    override fun liveState(): IntArray = synchronized(lock) {
        sessions.values.sortedBy { it.slot }.flatMap { s ->
            LiveState.of(s.slot, s.bridge, s.devices.source == GuitarSource.KERNEL_GUITAR, s.touchSeen, s.battery).toList()
        }.toIntArray()
    }

    override fun gravity(slot: Int): String = synchronized(lock) {
        sessions.values.firstOrNull { it.slot == slot }?.bridge?.gravity?.encode().orEmpty()
    }

    override fun beginCapture() {
        synchronized(lock) {
            captureWaiting = true
            captureResult = ""
        }
    }

    override fun captureResult(): String = synchronized(lock) { captureResult }

    override fun rumble(slot: Int, millis: Int) {
        val path = synchronized(lock) { sessions.values.firstOrNull { it.slot == slot }?.devices?.remote }
            ?.eventHandler?.let { "/dev/input/$it" } ?: return
        thread("wiitar-rumble") {
            Evdev.load(nativeLibraryDir)?.let {
                problem("rumble unavailable: $it")
                return@thread
            }
            val result = Evdev.rumble(path, millis.coerceIn(50, 2000))
            if (result != 0) problem("rumble failed on $path (errno ${-result})")
        }
    }

    override fun latency(): LongArray = synchronized(lock) { latency.summary() }

    /**
     * A context whose calls are attributed to com.android.shell, the package the shell uid
     * actually owns, so system services' caller checks accept the shell user's permissions.
     */
    private fun shellContext(): Context? {
        val base = context ?: return null
        return try {
            base.createPackageContext("com.android.shell", 0)
        } catch (e: Exception) {
            base
        }
    }

    override fun hostAddress(): String = try {
        val process = ProcessBuilder("settings", "get", "secure", "bluetooth_address").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        out.takeIf { WiimotePairing.isValidAddress(it) }?.let { WiimotePairing.normalizeAddress(it) }.orEmpty()
    } catch (e: Exception) {
        problem("couldn't read this device's Bluetooth address: $e")
        ""
    }

    /**
     * Uses the hidden BluetoothHidHost.connect() with the shell user's Bluetooth
     * permissions. Calls are attributed to com.android.shell, the package the shell
     * uid actually owns, so Bluetooth's caller checks accept them.
     */
    @SuppressLint("MissingPermission")
    override fun connectWiimotes(): String {
        val shell = shellContext() ?: return "No context in the bridge service."
        val adapter = shell.getSystemService(BluetoothManager::class.java)?.adapter ?: return "No Bluetooth adapter."
        val wiimotes = try {
            adapter.bondedDevices.filter { WiimotePairing.isWiimoteName(it.name) }
        } catch (e: Exception) {
            return "Can't list paired devices: $e"
        }
        if (wiimotes.isEmpty()) return "No paired Wii Remotes."
        val ready = CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                proxy = p
                ready.countDown()
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }
        if (!adapter.getProfileProxy(shell, listener, PROFILE_HID_HOST)) return "HID profile unavailable."
        if (!ready.await(3, TimeUnit.SECONDS)) return "HID profile didn't respond."
        val hid = proxy ?: return "HID profile unavailable."
        return try {
            val connect = hid.javaClass.getMethod("connect", BluetoothDevice::class.java)
            wiimotes.joinToString("\n") { device ->
                val state = hid.getConnectionState(device)
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    "${device.address}: already connected"
                } else {
                    try {
                        val ok = connect.invoke(hid, device) as? Boolean ?: false
                        "${device.address}: ${if (ok) "connecting…" else "Android refused"}"
                    } catch (e: InvocationTargetException) {
                        "${device.address}: ${e.targetException}"
                    }
                }
            }
        } catch (e: Exception) {
            "Couldn't connect: $e"
        } finally {
            adapter.closeProfileProxy(PROFILE_HID_HOST, hid)
        }
    }

    override fun guitars(): Array<String> = synchronized(lock) {
        sessions.values.sortedBy { it.slot }.map { "${it.slot}\t${it.id}\t${it.battery}" }.toTypedArray()
    }

    override fun status(): String = synchronized(lock) {
        buildString {
            appendLine(if (running) "Bridge running: ${sessions.size} guitar(s)" else "Bridge not running")
            for (s in sessions.values.sortedBy { it.slot }) {
                val d = s.devices
                appendLine(
                    "Guitar ${s.slot} (${s.id}, battery ${if (s.battery >= 0) "${s.battery}%" else "?"}${if (s.id in profiles) ", own settings" else ""}): " +
                        "\"$VIRTUAL_NAME ${s.slot}\" ← ${d.source} %s%s, accel %s, %d events".format(
                        d.guitar.eventHandler,
                        if (s.hidden) " [hidden from games]" else "",
                        d.accelerometer?.eventHandler ?: "none",
                        s.events,
                    ),
                )
                val st = s.bridge.state
                appendLine(
                    "  frets ${st.frets.joinToString().ifEmpty { "-" }} · strum ${if (st.strumUp) "UP" else if (st.strumDown) "DOWN" else "-"}" +
                        " · whammy ${s.bridge.effectiveWhammy()?.let { "%.2f".format(it) } ?: "n/a"}" +
                        " · tilt ${st.tilt?.let { "%.0f° → %.2f".format(it.degrees, it.amount) } ?: "n/a"}",
                )
                appendLine("  output: ${s.bridge.pressedButtons().joinToString().ifEmpty { "no buttons" }}")
            }
            val (n, avg, p95, max) = latency.summary().toList()
            appendLine(
                if (n == 0L) "Latency: no samples yet${if (!monotonicClock) " (clock not available)" else ""}"
                else "Latency (event → virtual gamepad): avg %.2f ms, p95 %.2f ms, max %.2f ms over %d presses"
                    .format(avg / 1000.0, p95 / 1000.0, max / 1000.0, n),
            )
            errors.forEach { appendLine("! $it") }
        }
    }

    private fun node(d: KernelInputDevice): String? =
        d.eventHandler?.let { "/dev/input/$it" }?.takeIf { File(it).canRead() }

    private fun problem(message: String) {
        synchronized(lock) {
            if (errors.lastOrNull() == message) return
            errors.addLast(message)
            while (errors.size > 10) errors.removeFirst()
        }
    }

    private fun thread(name: String, block: () -> Unit) {
        Thread({
            try {
                block()
            } catch (e: Exception) {
                problem("$name: $e")
            }
        }, name).apply { isDaemon = true }.start()
    }

    private companion object {
        const val VIRTUAL_NAME = "Wiitar Guitar"
        const val PROFILE_HID_HOST = 4 // BluetoothProfile.HID_HOST, hidden from the public SDK
        const val MAX_GUITARS = 8
        const val DISCOVERY_INTERVAL_MS = 1500L
        const val BATTERY_REFRESH_ROUNDS = 20 // about every 30 s
    }
}
