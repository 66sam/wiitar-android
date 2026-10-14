/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.wiitar.app.DiagnosticLog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.wiitar.app.BridgeClient
import io.wiitar.app.BridgeClient.ShizukuStatus
import io.wiitar.app.BridgeUiState
import io.wiitar.app.PairingActivity
import io.wiitar.bridge.LiveState

/**
 * The only screen most people need: what's missing to get going, then one
 * live guitar per player and a single Start/Stop button.
 */
@Composable
fun HomeScreen(state: BridgeUiState, onAdvanced: () -> Unit) {
    val context = LocalContext.current
    var connectNote by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WIITAR", Type.wordmark)
                Text("Wii guitars as Android controllers", Type.dim)
            }
            SecondaryButton("Advanced", onClick = onAdvanced)
        }

        val shizukuReady = state.shizuku == ShizukuStatus.READY
        val guitarReady = state.wiimotes > 0 && state.guitarAttached
        if (!shizukuReady || !guitarReady || !state.running) {
            Panel {
                Heading("Get playing")
                StepRow(
                    done = shizukuReady,
                    title = "Shizuku",
                    detail = when (state.shizuku) {
                        ShizukuStatus.NOT_INSTALLED -> "Install Shizuku (free). It lets Wiitar work without root."
                        ShizukuStatus.NOT_RUNNING -> "Start it in the Shizuku app, via Wireless debugging. Needed again after a reboot."
                        ShizukuStatus.NEEDS_PERMISSION -> "Allow Wiitar in Shizuku."
                        ShizukuStatus.READY -> "Ready"
                    },
                    action = when (state.shizuku) {
                        ShizukuStatus.NOT_INSTALLED -> "Get it"
                        ShizukuStatus.NOT_RUNNING -> "Open"
                        ShizukuStatus.NEEDS_PERMISSION -> "Allow"
                        ShizukuStatus.READY -> null
                    },
                    onAction = {
                        if (state.shizuku == ShizukuStatus.NEEDS_PERMISSION) BridgeClient.requestPermission()
                        else BridgeClient.openShizuku(context)
                    },
                )
                StepRow(
                    done = guitarReady,
                    title = "Guitar",
                    detail = when {
                        guitarReady -> "Wii Remote and guitar connected"
                        state.wiimotes > 0 -> "Wii Remote connected. Plug the guitar into it."
                        connectNote.isNotEmpty() -> connectNote
                        else -> "Press any button on the Wii Remote (if paired with SYNC), " +
                            "or hold 1 + 2 and tap Connect."
                    },
                    action = if (state.wiimotes == 0) "Connect" else null,
                    onAction = {
                        val service = BridgeClient.service
                        if (service == null) {
                            // Without the Shizuku service, Android's own Bluetooth screen is the way to connect.
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        } else {
                            connectNote = "Connecting… keep the Wii Remote's lights blinking."
                            BridgeClient.call {
                                val report = service.connectWiimotes()
                                connectNote = if (report.contains("connecting") || report.contains("already")) {
                                    "Connecting…"
                                } else {
                                    "Couldn't connect automatically ($report). Tap the Wii Remote in Bluetooth settings."
                                }
                                DiagnosticLog.add("[connect] $report")
                            }
                        }
                    },
                )
                Text(
                    "Pair a new Wii Remote",
                    Type.dim.copy(color = Palette.amber),
                    Modifier.clickable { context.startActivity(Intent(context, PairingActivity::class.java)) },
                )
                StepRow(
                    done = state.running,
                    title = "Bridge",
                    detail = when {
                        state.running -> "Running"
                        BridgeClient.autoStart -> "Starts by itself once Shizuku is ready, or tap Start."
                        else -> "Tap Start below."
                    },
                )
            }
        }

        if (state.running) {
            if (state.live.isEmpty()) {
                Panel {
                    Heading("Waiting for a guitar")
                    Text("Connect a Wii Remote with a guitar plugged in. It's picked up automatically.", Type.dim)
                }
            }
            for (live in state.live) {
                val slot = live[LiveState.SLOT]
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Heading("Player $slot", Modifier.weight(1f))
                        val battery = live[LiveState.BATTERY]
                        if (battery >= 0) {
                            Text(
                                "Battery $battery%  ",
                                Type.dim.copy(color = if (battery <= 15) Palette.warn else Palette.textDim),
                            )
                        }
                        SecondaryButton("Buzz") { BridgeClient.service?.let { s -> BridgeClient.call { s.rumble(slot, 400) } } }
                    }
                    GuitarView(live)
                    Text("In your game, bind the controller named “Wiitar Guitar $slot”.", Type.dim)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            text = if (state.running) "Stop" else "Start",
            enabled = state.running || shizukuReady,
        ) {
            if (state.running) BridgeClient.stop() else BridgeClient.start()
        }
    }
}
