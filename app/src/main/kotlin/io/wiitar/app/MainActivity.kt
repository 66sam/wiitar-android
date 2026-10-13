/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.wiitar.app.ui.AdvancedScreen
import io.wiitar.app.ui.ControlsScreen
import io.wiitar.app.ui.HomeScreen
import io.wiitar.app.ui.LatencyScreen
import io.wiitar.app.ui.Palette
import io.wiitar.app.ui.TiltScreen
import io.wiitar.bridge.LatencyStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class Screen { HOME, ADVANCED, CONTROLS, TILT, LATENCY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(android.graphics.Color.rgb(0x12, 0x10, 0x0E))
        setContent { WiitarApp() }
    }

    /**
     * Keys from our own virtual gamepads are consumed (the guitar shouldn't
     * click around this UI) and timed: Android's event time vs now is the
     * delay from the virtual device to an app.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val name = event.device?.name.orEmpty()
        if (name.startsWith(VIRTUAL_GAMEPAD_NAME)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                synchronized(AppLatency) { AppLatency.stats.add((SystemClock.uptimeMillis() - event.eventTime) * 1000) }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val VIRTUAL_GAMEPAD_NAME = "Wiitar Guitar"
    }
}

/** Android-side delivery delay of our virtual gamepads' key events, measured in [MainActivity]. */
object AppLatency {
    val stats = LatencyStats(capacity = 200)
}

@Composable
fun WiitarApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) {
        screen = if (screen == Screen.ADVANCED) Screen.HOME else Screen.ADVANCED
    }
    val bridge by rememberBridgeState()
    Box(Modifier.fillMaxSize().background(Palette.background).systemBarsPadding()) {
        when (screen) {
            Screen.HOME -> HomeScreen(bridge, onAdvanced = { screen = Screen.ADVANCED })
            Screen.ADVANCED -> AdvancedScreen(onOpen = { screen = it }, onBack = { screen = Screen.HOME })
            Screen.CONTROLS -> ControlsScreen(bridge, onBack = { screen = Screen.ADVANCED })
            Screen.TILT -> TiltScreen(bridge, onBack = { screen = Screen.ADVANCED })
            Screen.LATENCY -> LatencyScreen(bridge, onBack = { screen = Screen.ADVANCED })
        }
    }
}

/** What the screens need to know, refreshed continuously while the app is visible. */
data class BridgeUiState(
    val shizuku: BridgeClient.ShizukuStatus = BridgeClient.ShizukuStatus.NOT_RUNNING,
    val running: Boolean = false,
    /** Wii Remotes Android sees connected. */
    val wiimotes: Int = 0,
    /** Whether any of them has a guitar plugged in. */
    val guitarAttached: Boolean = false,
    /** [io.wiitar.bridge.LiveState] slices, one per bridged guitar. */
    val live: List<IntArray> = emptyList(),
)

@Composable
fun rememberBridgeState(): State<BridgeUiState> {
    val state = remember { mutableStateOf(BridgeUiState()) }
    LaunchedEffect(Unit) {
        var tick = 0
        var slow = BridgeUiState()
        while (true) {
            // Cheap live data every frame-ish; Shizuku and device checks a few times a second.
            if (tick++ % 10 == 0) {
                slow = BridgeUiState(
                    shizuku = BridgeClient.shizukuState(),
                    running = BridgeClient.running,
                    wiimotes = countDevices { it == "Nintendo Wii Remote" },
                    guitarAttached = countDevices { it.endsWith("Guitar") } > 0,
                )
            }
            val service = BridgeClient.service
            val raw = if (service != null && BridgeClient.running) {
                withContext(Dispatchers.IO) { runCatching { service.liveState() }.getOrNull() }
            } else {
                null
            }
            val size = io.wiitar.bridge.LiveState.SIZE
            val live = raw?.let { r -> (0 until r.size / size).map { r.copyOfRange(it * size, (it + 1) * size) } }.orEmpty()
            state.value = slow.copy(running = BridgeClient.running, live = live)
            delay(33)
        }
    }
    return state
}

private fun countDevices(matches: (String) -> Boolean): Int =
    InputDevice.getDeviceIds().count { id -> InputDevice.getDevice(id)?.name?.let(matches) == true }
