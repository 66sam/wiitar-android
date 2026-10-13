/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.content.Context
import kotlin.system.exitProcess

/**
 * Shizuku user service: Shizuku starts this class in its own process as the
 * ADB shell user (uid 2000), which can read /dev/input/event* (group input)
 * and write /dev/uhid (group uhid) without root.
 */
class ShellService() : IShellService.Stub() {
    /** Shizuku instantiates user services through either constructor. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() = exitProcess(0)

    override fun uid(): Int = android.os.Process.myUid()

    override fun run(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                // Stay well under the 1 MB binder transaction limit.
                if (text.length < MAX_OUTPUT) text.append(buffer, 0, minOf(n, MAX_OUTPUT - text.length))
            }
            text.toString()
        }
        val exit = process.waitFor()
        val truncated = if (output.length >= MAX_OUTPUT) "\n[output truncated]" else ""
        return output + truncated + if (exit != 0) "\n[exit $exit]" else ""
    }

    private companion object {
        const val MAX_OUTPUT = 300_000
    }
}
