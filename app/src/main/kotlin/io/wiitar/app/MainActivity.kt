/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = scrollingColumn()
        column.heading("Wiitar")
        column.paragraph(
            "Step 1: pair your Wii Remote (guitar plugged in) with the pairing helper. " +
                "It answers the Wiimote's PIN request, which can't be typed by hand.\n\n" +
                "Step 2: open Input diagnostics and press every fret, strum, whammy and tilt. " +
                "Then tap \"Copy report\" and send the text back.",
        )
        column.button("Pair a Wii Remote") { startActivity(Intent(this, PairingActivity::class.java)) }
        column.button("Input diagnostics") { startActivity(Intent(this, InputDiagnosticsActivity::class.java)) }
        column.monospace(
            "\nv${packageManager.getPackageInfo(packageName, 0).versionName} · " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · kernel ${System.getProperty("os.version")}",
        )
    }
}
