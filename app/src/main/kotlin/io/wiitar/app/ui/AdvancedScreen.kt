/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.wiitar.app.BridgeClient
import io.wiitar.app.GuidedTestActivity
import io.wiitar.app.InputDiagnosticsActivity
import io.wiitar.app.PairingActivity
import io.wiitar.app.ProbeActivity
import io.wiitar.app.Screen

/** Back arrow + title, then scrolling content. */
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.clickable(onClick = onBack).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹  ", Type.wordmark.copy(fontSize = Type.wordmark.fontSize * 0.8f))
            Text(title.uppercase(), Type.wordmark.copy(fontSize = Type.wordmark.fontSize * 0.7f, color = Type.body.color))
        }
        content()
    }
}

@Composable
fun AdvancedScreen(onOpen: (Screen) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    fun launch(cls: Class<*>) = context.startActivity(Intent(context, cls))
    ScreenFrame("Advanced", onBack) {
        Panel {
            var autoStart by remember { mutableStateOf(BridgeClient.autoStart) }
            ToggleRow(
                "Start automatically",
                "Start the bridge whenever Shizuku is running: when the app opens, when Shizuku starts, " +
                    "and when a Wii Remote connects.",
                autoStart,
            ) {
                BridgeClient.autoStart = it
                autoStart = it
            }
        }
        Panel {
            Heading("Set up")
            NavRow("Controls", "Press a guitar control to assign it, touch strip, gamepad buttons") { onOpen(Screen.CONTROLS) }
            NavRow("Tilt", "Calibrate star power tilt for how you hold the guitar") { onOpen(Screen.TILT) }
            NavRow("Pair a Wii Remote", "Bluetooth pairing helper (answers the PIN for you)") { launch(PairingActivity::class.java) }
        }
        Panel {
            Heading("Check")
            NavRow("Latency & status", "How long the bridge takes, and what it's doing") { onOpen(Screen.LATENCY) }
            NavRow("Guided guitar test", "Records what each control sends, one at a time") { launch(GuidedTestActivity::class.java) }
            NavRow("Input diagnostics", "Every input device and live event Android sees") { launch(InputDiagnosticsActivity::class.java) }
            NavRow("System probe", "Low-level checks through Shizuku, for bug reports") { launch(ProbeActivity::class.java) }
        }
    }
}
