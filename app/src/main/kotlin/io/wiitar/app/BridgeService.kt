/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.content.Context
import io.wiitar.bridge.BridgeMapping
import io.wiitar.bridge.EvdevEvent
import io.wiitar.bridge.GuitarBridge
import io.wiitar.bridge.GuitarSource
import io.wiitar.bridge.KernelInputDevice
import io.wiitar.bridge.Uhid
import io.wiitar.bridge.VirtualGamepad
import io.wiitar.protocol.Axis
import io.wiitar.protocol.TiltConfig
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/**
 * Shizuku user service (ADB shell uid, no root) that reads the guitar's evdev
 * devices and feeds a virtual gamepad created through /dev/uhid.
 *
 * Runs as a daemon so it keeps going while a game is in the foreground. It is
 * stopped by unbinding with remove = true, which calls [destroy]; exiting
 * closes /dev/uhid, and the kernel then removes the virtual gamepad.
 */
class BridgeService() : IBridgeService.Stub() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private val lock = Any()
    private var bridge: GuitarBridge? = null
    private var uhid: RandomAccessFile? = null
    private val sources = mutableListOf<String>()
    private val errors = ArrayDeque<String>()
    private var eventsIn = 0L
    private var reportsOut = 0L
    private var accelRaw = IntArray(3)

    override fun destroy() {
        synchronized(lock) {
            try {
                uhid?.write(Uhid.destroy())
            } catch (e: Exception) {
                // Closing the fd on exit removes the device anyway.
            }
        }
        exitProcess(0)
    }

    override fun start(tiltAxis: Int, tiltInvert: Boolean): String {
        synchronized(lock) {
            if (bridge != null) return status()
            try {
                startLocked(TiltConfig(axis = Axis.entries[tiltAxis.coerceIn(0, 2)], invert = tiltInvert))
            } catch (e: Exception) {
                problem("start failed: $e")
            }
        }
        return status()
    }

    private fun startLocked(tilt: TiltConfig) {
        val devices = KernelInputDevice.parseAll(File("/proc/bus/input/devices").readText())
        fun node(d: KernelInputDevice) = d.eventHandler?.let { "/dev/input/$it" }?.takeIf { File(it).canRead() }

        val guitars = devices.filter { it.name == GUITAR_NAME }
        val kernelGuitar = guitars.firstOrNull { it.vendor == NINTENDO && node(it) != null }
        val odinGuitar = guitars.firstOrNull { it.vendor != NINTENDO && node(it) != null }
        val accel = devices.firstOrNull { it.name == ACCEL_NAME && node(it) != null }
        guitars.filter { node(it) == null }.forEach {
            problem("${it.name} %04x:%04x (${it.eventHandler}) exists but /dev/input/${it.eventHandler} isn't readable".format(it.vendor, it.product))
        }

        // Prefer the kernel's own device (it has whammy); otherwise use the Odin's copy.
        val guitar = kernelGuitar?.let { it to GuitarSource.KERNEL_GUITAR }
            ?: odinGuitar?.let { it to GuitarSource.ODIN_GUITAR }
        if (guitar == null) {
            problem("No readable \"$GUITAR_NAME\" input device. Is the Wii Remote connected with the guitar plugged in?")
            return
        }

        val newBridge = GuitarBridge(BridgeMapping(tilt = tilt))
        val device = RandomAccessFile("/dev/uhid", "rw")
        device.write(Uhid.create2(VIRTUAL_NAME, VirtualGamepad.DESCRIPTOR, VirtualGamepad.VENDOR, VirtualGamepad.PRODUCT))
        device.write(Uhid.input2(newBridge.report()))
        uhid = device
        bridge = newBridge
        drainUhid(device)

        read(guitar.first, guitar.second, node(guitar.first)!!)
        if (accel != null) read(accel, GuitarSource.ACCELEROMETER, node(accel)!!) else problem("No readable accelerometer device; tilt disabled.")
    }

    /** One blocking reader thread per evdev device. */
    private fun read(device: KernelInputDevice, source: GuitarSource, path: String) {
        sources += "$source: \"${device.name}\" %04x:%04x $path".format(device.vendor, device.product)
        val size = if (System.getProperty("os.arch").orEmpty().contains("64")) EvdevEvent.SIZE_64 else EvdevEvent.SIZE_32
        thread("wiitar-$source") {
            FileInputStream(path).use { input ->
                val buffer = ByteArray(size * 64)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) {
                        problem("$path closed (device disconnected?)")
                        return@use
                    }
                    var offset = 0
                    while (offset + size <= n) {
                        handle(source, EvdevEvent.decode(buffer, offset, size))
                        offset += size
                    }
                }
            }
        }
    }

    private fun handle(source: GuitarSource, event: EvdevEvent) {
        synchronized(lock) {
            eventsIn++
            if (source == GuitarSource.ACCELEROMETER && event.type == EvdevEvent.EV_ABS && event.code in 3..5) {
                accelRaw[event.code - 3] = event.value
            }
            val report = bridge?.onEvent(source, event) ?: return
            try {
                uhid?.write(Uhid.input2(report))
                reportsOut++
            } catch (e: Exception) {
                problem("uhid write failed: $e")
            }
        }
    }

    /** Reads (and ignores) the kernel's messages to us so its queue never fills up. */
    private fun drainUhid(device: RandomAccessFile) = thread("wiitar-uhid") {
        val buffer = ByteArray(Uhid.EVENT_SIZE)
        while (device.read(buffer) > 0) Unit
    }

    override fun status(): String = synchronized(lock) {
        buildString {
            val b = bridge
            appendLine(if (b != null) "Bridge running: virtual gamepad \"$VIRTUAL_NAME\"" else "Bridge not running")
            sources.forEach { appendLine("  source $it") }
            appendLine("  events in: $eventsIn, reports out: $reportsOut")
            if (b != null) {
                val s = b.state
                appendLine("Frets: ${s.frets.joinToString().ifEmpty { "-" }}  strum: ${if (s.strumUp) "UP" else if (s.strumDown) "DOWN" else "-"}  plus: ${s.plus}  minus: ${s.minus}")
                appendLine("Stick: %+.2f %+.2f  whammy: %s  touch strip: %s".format(
                    s.stickX, s.stickY,
                    s.whammy?.let { "%.2f".format(it) } ?: "n/a",
                    s.touchStrip?.let { "%+.2f".format(it) } ?: "-",
                ))
                appendLine("Tilt: %s  (accel raw X %d, Y %d, Z %d)".format(
                    s.tilt?.let { "%.0f° → %.2f".format(it.degrees, it.amount) } ?: "n/a",
                    accelRaw[0], accelRaw[1], accelRaw[2],
                ))
            }
            errors.forEach { appendLine("! $it") }
        }
    }

    private fun problem(message: String) {
        synchronized(lock) {
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
        const val GUITAR_NAME = "Nintendo Wii Remote Guitar"
        const val ACCEL_NAME = "Nintendo Wii Remote Accelerometer"
        const val VIRTUAL_NAME = "Wiitar Guitar"
        const val NINTENDO = 0x057e
    }
}
