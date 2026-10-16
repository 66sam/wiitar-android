/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.wiitar.app.BridgeClient
import io.wiitar.app.BridgeUiState
import io.wiitar.bridge.ButtonLayout
import io.wiitar.bridge.GuitarControl
import io.wiitar.bridge.GuitarSource
import io.wiitar.bridge.InputLayout
import io.wiitar.bridge.LiveState
import io.wiitar.bridge.PhysicalInput
import io.wiitar.bridge.TouchStripMode
import io.wiitar.bridge.VirtualGamepad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ControlsScreen(state: BridgeUiState, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-read the saved config whenever something here changes it.
    var version by remember { mutableIntStateOf(0) }
    var profileId by remember { mutableStateOf<String?>(null) }
    val config = remember(version, profileId) { BridgeClient.configFor(profileId) }
    fun update(change: (io.wiitar.bridge.BridgeConfig) -> io.wiitar.bridge.BridgeConfig) {
        BridgeClient.updateConfig(profileId, change)
        version++
    }

    // Bindings shown for the kind of guitar device actually connected.
    val source = if (state.live.firstOrNull()?.get(LiveState.KERNEL_SOURCE) == 0) GuitarSource.ODIN_GUITAR else GuitarSource.KERNEL_GUITAR
    var capturing by remember { mutableStateOf<GuitarControl?>(null) }
    var captureNote by remember { mutableStateOf("") }

    LaunchedEffect(capturing) {
        val control = capturing ?: return@LaunchedEffect
        val service = BridgeClient.service
        if (service == null || !state.running) {
            captureNote = "Start the bridge (Home → Start) first, with the guitar connected."
            capturing = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) { runCatching { service.beginCapture() } }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val result = withContext(Dispatchers.IO) { runCatching { service.captureResult() }.getOrDefault("") }
            if (result.isNotEmpty()) {
                val parts = result.split('|')
                val src = GuitarSource.entries.firstOrNull { it.name == parts.getOrNull(0) }
                val input = parts.getOrNull(1)?.let { PhysicalInput.decode(it) }
                if (src != null && input != null) {
                    update { it.copy(inputs = it.inputs.with(src, control, input)) }
                    captureNote = "${control.label} set to $input."
                }
                capturing = null
                return@LaunchedEffect
            }
            delay(80)
        }
        captureNote = "Nothing pressed. Try again."
        capturing = null
    }

    ScreenFrame("Controls", onBack) {
        if (state.guitars.isNotEmpty()) {
            Panel {
                Heading("Settings for")
                ProfilePicker(state.guitars, profileId) {
                    profileId = it
                    version++
                }
            }
        }
        Panel {
            Heading("Guitar controls")
            Text("Tap a control, then press it on the guitar.", Type.dim)
            val bindings = config.inputs.forSource(source)
            GuitarControl.entries.forEach { control ->
                ControlRow(
                    control = control,
                    binding = bindings[control]?.toString() ?: "not set",
                    waiting = capturing == control,
                    onClick = {
                        captureNote = ""
                        capturing = if (capturing == control) null else control
                    },
                )
            }
            if (captureNote.isNotEmpty()) Text(captureNote, Type.dim.copy(color = Palette.amber))
            SecondaryButton("Reset to default") { update { it.copy(inputs = InputLayout.DEFAULT) } }
        }

        Panel {
            Heading("Touch strip")
            Segmented(TouchStripMode.entries, config.touchStripMode, { it.label }) { mode ->
                update { it.copy(touchStripMode = mode) }
            }
            Text(
                when (config.touchStripMode) {
                    TouchStripMode.AXIS -> "Sent as its own axis. Clone Hero doesn't use it."
                    TouchStripMode.FRETS -> "Touching a section presses that fret, so you can tap notes on the strip."
                    TouchStripMode.WHAMMY -> "The strip works as the whammy bar when the real one isn't readable."
                    TouchStripMode.OFF -> "Ignored."
                } + if (source == GuitarSource.ODIN_GUITAR) {
                    " On this device the strip only reports left/centre/right, so “Press frets” can't tell sections " +
                        "apart, and the real whammy bar isn't available: choose Whammy to use the strip instead."
                } else {
                    ""
                },
                Type.dim,
            )
        }

        Panel {
            Heading("Gamepad buttons")
            Text("What each control presses on “Wiitar Guitar”. Only change this if a game needs it.", Type.dim)
            OutputButtons(config.mapping(), onChange = { input, button ->
                update { c -> c.copy(buttons = ButtonLayout.encode(ButtonLayout.set(c.mapping(), input, button))) }
            })
            SecondaryButton("Reset buttons") { update { it.copy(buttons = "") } }
        }

        Panel {
            ToggleRow(
                "Hide the guitar's own device",
                "Games then only see “Wiitar Guitar”, so they can't bind the wrong one. Recommended.",
                config.hideGuitar,
            ) { on -> update { it.copy(hideGuitar = on) } }
        }
    }
}
