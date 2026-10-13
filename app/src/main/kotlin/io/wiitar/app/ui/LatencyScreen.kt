/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.wiitar.app.AppLatency
import io.wiitar.app.BridgeClient
import io.wiitar.app.BridgeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LatencyScreen(state: BridgeUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    var bridge by remember { mutableStateOf(LongArray(4)) }
    var app by remember { mutableStateOf(LongArray(4)) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val service = BridgeClient.service
            if (service != null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        bridge = service.latency()
                        status = service.status()
                    }
                }
            }
            app = synchronized(AppLatency) { AppLatency.stats.summary() }
            delay(500)
        }
    }

    ScreenFrame("Latency & status", onBack) {
        Panel {
            Heading("Latency")
            Text("Press frets and strum on this screen for a few seconds.", Type.dim)
            LatencyLine("Bridge", "guitar event → virtual gamepad", bridge)
            LatencyLine("Android", "virtual gamepad → app", app)
            if (bridge[0] > 0 && app[0] > 0) {
                Text(
                    "Added by Wiitar + Android: about %.1f ms on average.".format((bridge[1] + app[1]) / 1000.0),
                    Type.body.copy(color = Palette.amber),
                )
            }
            Text(
                "Not included: the Wii Remote's own Bluetooth link (it reports about every 10 ms), and the game's " +
                    "audio/video delay, which Clone Hero's calibration covers.",
                Type.dim,
            )
        }
        Panel {
            Heading("Bridge status")
            Text(status.ifEmpty { if (state.running) "…" else "Bridge not running." }, Type.mono)
            SecondaryButton("Copy") {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("wiitar status", status))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun LatencyLine(title: String, what: String, stats: LongArray) {
    val (n, avg, p95, max) = stats.toList()
    Text(
        if (n == 0L) "$title ($what): no samples yet"
        else "$title ($what): avg %.1f ms · 95%% under %.1f ms · max %.1f ms  (%d)".format(avg / 1000.0, p95 / 1000.0, max / 1000.0, n),
        Type.body,
    )
}
