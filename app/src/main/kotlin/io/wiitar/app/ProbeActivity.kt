/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Checks what the non-root "Shizuku" path can reach on this device, before
 * the bridge is built on top of it:
 *  - does the app get ADB-shell privileges (uid 2000) through Shizuku?
 *  - which evdev nodes does the kernel's hid-wiimote driver create, and do the
 *    whammy bar and accelerometer appear there even though Android hides them?
 *  - is /dev/uhid writable (needed to create our own virtual gamepad)?
 *
 * Only fixed commands from this file are run.
 */
class ProbeActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private var output = StringBuilder()

    @Volatile
    private var shell: IShellService? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(SERVICE_VERSION)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val service = IShellService.Stub.asInterface(binder)
            shell = service
            background {
                val uid = service.uid()
                status("Connected through Shizuku as uid $uid${if (uid == 2000) " (ADB shell) ✓" else ""}. Run the probe.")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            status("Shizuku service disconnected.")
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode != REQUEST_SHIZUKU) return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) bindShell() else status("Shizuku permission denied.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = scrollingColumn()
        column.paragraph(
            "This checks whether whammy and tilt can be reached without root, using Shizuku.\n\n" +
                "1. Install Shizuku (Play Store or github.com/RikkaApps/Shizuku/releases).\n" +
                "2. In Shizuku, follow \"Start via Wireless debugging\" (needs Developer options → Wireless debugging). " +
                "No PC needed. Repeat after each reboot.\n" +
                "3. Connect the Wii Remote + guitar, then come back here.",
        )
        statusView = column.paragraph()
        column.button("Connect to Shizuku") { connect() }
        column.button("Run system probe") { runProbe() }
        column.button("Record raw input for 10 s") { record() }
        column.button("Copy output") { copyToClipboard("wiitar probe", reportHeader() + output) }
        outputView = column.monospace()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        status(shizukuState())
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (shell != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            } catch (e: Exception) {
                // Shizuku went away; nothing to clean up.
            }
        }
        super.onDestroy()
    }

    private fun shizukuState(): String = when {
        !Shizuku.pingBinder() -> "Shizuku is not running. Install it and start it via Wireless debugging."
        Shizuku.isPreV11() -> "This Shizuku version is too old; please update it."
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku is running. Tap Connect and allow Wiitar."
        else -> "Shizuku is running and Wiitar is allowed (Shizuku uid ${Shizuku.getUid()}). Tap Connect."
    }

    private fun connect() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            status(shizukuState())
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindShell()
        } else {
            Shizuku.requestPermission(REQUEST_SHIZUKU)
        }
    }

    private fun bindShell() {
        status("Starting the Shizuku service…")
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            status("Couldn't start the Shizuku service: $e")
        }
    }

    private fun runProbe() {
        val service = shell ?: return status("Connect to Shizuku first.")
        output = StringBuilder()
        status("Running probe…")
        background {
            for ((title, command) in PROBE_COMMANDS) {
                val result = try {
                    service.run(command)
                } catch (e: Exception) {
                    "[failed: $e]"
                }
                append("\n===== $title =====\n$ $command\n$result")
            }
            status("Probe done. Tap Copy output and send it back.")
        }
    }

    private fun record() {
        val service = shell ?: return status("Connect to Shizuku first.")
        status("Recording for 10 s: press each fret, strum, push the whammy bar, tilt the guitar, slide the touch strip…")
        background {
            val result = try {
                service.run("timeout 10 getevent -lt")
            } catch (e: Exception) {
                "[failed: $e]"
            }
            append("\n===== raw input (10 s) =====\n$result")
            status("Recording done. Tap Copy output and send it back.")
        }
    }

    private fun reportHeader() =
        "Wiitar probe\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "${statusView.text}\n"

    private fun append(text: String) = runOnUiThread {
        output.append(text).append('\n')
        // Long recordings are copied in full but only the tail is shown on screen.
        outputView.text = if (output.length > SCREEN_CHARS) "…\n" + output.takeLast(SCREEN_CHARS) else output
    }

    private fun status(text: String) = runOnUiThread {
        statusView.text = text
        DiagnosticLog.add("[probe] $text")
    }

    private fun background(block: () -> Unit) = Thread(Runnable { block() }, "wiitar-probe").start()

    private companion object {
        const val REQUEST_SHIZUKU = 42
        const val SERVICE_VERSION = 1
        const val SCREEN_CHARS = 20_000

        val PROBE_COMMANDS = listOf(
            "identity" to "id",
            "device nodes" to "ls -l /dev/uhid /dev/uinput /dev/hidraw* 2>&1; ls -l /dev/input/",
            "access" to "for f in /dev/uhid /dev/uinput; do " +
                "if [ -w \$f ]; then echo \"\$f writable\"; else echo \"\$f NOT writable\"; fi; done; " +
                "for f in /dev/input/event*; do if [ -r \$f ]; then echo \"\$f readable\"; else echo \"\$f NOT readable\"; fi; done",
            "kernel input devices" to "cat /proc/bus/input/devices",
            "evdev capabilities" to "getevent -lp",
            "HID devices and drivers" to "for d in /sys/bus/hid/devices/*; do echo \"\$d -> \$(readlink \$d/driver)\"; done",
            "evdev nodes by name" to "for e in /sys/class/input/event*; do " +
                "echo \"\${e##*/} dev=\$(cat \$e/dev) name=\$(cat \$e/device/name) node=\$(ls -l /dev/input/\${e##*/} 2>&1)\"; done",
            "char devices 13:x outside /dev/input" to "ls -lR /dev 2>/dev/null | grep -E '^c.* 13, ' | grep -v ' event[0-9]' | head -20",
            "AYN / Odin packages" to "pm list packages | grep -iE 'ayn|odin|gamepad|controller|keymap|input'",
            "AYN / Odin processes" to "ps -A -o PID,USER,NAME | grep -iE 'ayn|odin|gamepad|controller|keymap|input'",
            "Odin-related settings" to "for ns in global system secure; do " +
                "settings list \$ns | grep -iE 'ayn|odin|gamepad|controller|joystick|keymap|mapping' | sed \"s/^/\$ns: /\"; done",
            "Android input service" to "dumpsys input | grep -iE -A4 'Nintendo|Wiitar|Excluded|2020' | head -120",
        )
    }
}
