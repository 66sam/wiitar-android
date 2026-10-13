/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import io.wiitar.app.BridgeClient
import io.wiitar.app.BridgeUiState
import io.wiitar.bridge.BridgeMapping
import io.wiitar.bridge.LiveState
import io.wiitar.bridge.TiltCalibration
import io.wiitar.bridge.Vec3
import io.wiitar.protocol.Axis

@Composable
fun TiltScreen(state: BridgeUiState, onBack: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val config = remember(version) { BridgeClient.config() }
    var rest by remember { mutableStateOf<Vec3?>(null) }
    var note by remember { mutableStateOf("") }
    val live = state.live.firstOrNull()
    val slot = live?.get(LiveState.SLOT) ?: 1

    /** Reads guitar [slot]'s current gravity direction from the bridge, then calls [then] with it. */
    fun withGravity(then: (Vec3) -> Unit) {
        val service = BridgeClient.service
        if (service == null || live == null) {
            note = "Start the bridge with the guitar connected first."
            return
        }
        BridgeClient.call {
            val g = Vec3.decode(service.gravity(slot))
            if (g == null) note = "No motion data yet. Is the guitar connected?" else then(g)
        }
    }

    ScreenFrame("Tilt", onBack) {
        Panel {
            Heading("Live")
            val tilt = live?.get(LiveState.TILT) ?: -1
            TiltMeter(tilt, starPower = live?.get(LiveState.STAR_POWER) == 1)
            Text(
                when {
                    live == null -> "Start the bridge to see tilt."
                    tilt < 0 -> "This guitar isn't sending motion data."
                    else -> "Tilt ${tilt / 10}% · star power at ${(BridgeMapping().tiltButtonThreshold * 100).toInt()}%"
                },
                Type.dim,
            )
        }

        Panel {
            Heading("Calibrate")
            Text(
                "Tilt depends on how the Wii Remote sits in your guitar, so show Wiitar both positions.",
                Type.dim,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("1 · Playing position", Modifier.weight(1f)) {
                    withGravity {
                        rest = it
                        note = "Got it. Now raise the neck like you would for star power, and tap 2."
                    }
                }
                SecondaryButton("2 · Neck raised", Modifier.weight(1f)) {
                    val r = rest
                    if (r == null) {
                        note = "Do step 1 first."
                    } else {
                        withGravity { up ->
                            val calibration = TiltCalibration(r, up)
                            if (!calibration.usable) {
                                note = "Those two positions were too similar. Raise the neck further and try step 2 again."
                            } else {
                                BridgeClient.updateConfig { it.copy(tiltCalibration = calibration) }
                                rest = null
                                note = "Calibrated."
                                version++
                            }
                        }
                    }
                }
            }
            Text(
                note.ifEmpty { if (config.tiltCalibration?.usable == true) "Calibrated ✓" else "Not calibrated yet." },
                Type.dim.copy(color = Palette.amber),
            )
            if (config.tiltCalibration != null) {
                SecondaryButton("Clear calibration") {
                    BridgeClient.updateConfig { it.copy(tiltCalibration = null) }
                    version++
                }
            }
        }

        if (config.tiltCalibration == null) {
            Panel {
                Heading("Without calibration")
                Text("Accelerometer axis used for tilt:", Type.dim)
                Segmented(Axis.entries, config.tiltAxis, { it.name }) { axis ->
                    BridgeClient.updateConfig { it.copy(tiltAxis = axis) }
                    version++
                }
                ToggleRow("Invert", "Use if tilt goes the wrong way.", config.tiltInvert) { on ->
                    BridgeClient.updateConfig { it.copy(tiltInvert = on) }
                    version++
                }
            }
        }
    }
}

@Composable
private fun TiltMeter(tilt: Int, starPower: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Palette.panelRaised, Offset.Zero, size, r)
        if (tilt > 0) {
            drawRoundRect(
                if (starPower) Palette.amber else Palette.textDim,
                Offset.Zero, Size(size.width * tilt / 1000f, size.height), r,
            )
        }
        val threshold = size.width * BridgeMapping().tiltButtonThreshold
        drawLine(Palette.text, Offset(threshold, 0f), Offset(threshold, size.height), strokeWidth = 2f)
    }
}
