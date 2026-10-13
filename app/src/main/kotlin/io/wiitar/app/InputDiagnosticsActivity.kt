/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.app.Activity
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import kotlin.math.abs

/**
 * Shows every input device Android knows about, and the live key and axis
 * events from external ones. This answers "did the kernel's hid-wiimote driver
 * create a guitar device, and what does each fret/strum/whammy send?", which
 * is what Clone Hero and YARG will see.
 */
class InputDiagnosticsActivity : Activity(), InputManager.InputDeviceListener {
    private lateinit var inputManager: InputManager
    private lateinit var devicesView: TextView
    private lateinit var liveView: TextView
    private lateinit var logView: TextView
    private lateinit var bluetoothView: TextView

    /** Latest axis values per device id, for the live view. */
    private val liveAxes = LinkedHashMap<Int, String>()
    private val pressedKeys = HashMap<Int, MutableSet<String>>()

    /** Last value logged per (device, axis); only changes larger than [AXIS_LOG_STEP] are logged. */
    private val loggedAxes = HashMap<Pair<Int, Int>, Float>()

    private val logListener: () -> Unit = { runOnUiThread { logView.text = DiagnosticLog.text(lastLines = 80) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
        val column = scrollingColumn()
        column.paragraph(
            "Press every fret, strum up/down, plus/minus, move the whammy bar and the stick, tilt the guitar, " +
                "and slide along the touch strip if it has one. Then tap Copy report and send it back.",
        )
        column.button("Copy report") { copyToClipboard("wiitar input report", report()) }
        bluetoothView = column.monospace()
        column.heading("Live")
        liveView = column.monospace("(press something on an external controller)")
        column.heading("Input devices")
        devicesView = column.monospace()
        column.heading("Event log")
        logView = column.monospace()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, null)
        DiagnosticLog.addListener(logListener)
        refreshDevices()
        logListener()
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        DiagnosticLog.removeListener(logListener)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        log("device added: ${InputDevice.getDevice(deviceId)?.let { summary(it) } ?: deviceId}")
        refreshDevices()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        log("device removed: #$deviceId")
        liveAxes.remove(deviceId)
        refreshDevices()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        // Built-in devices (e.g. the touchscreen) report changes constantly; only external ones are interesting.
        val device = InputDevice.getDevice(deviceId)
        if (device == null || device.isController) log("device changed: ${device?.let { summary(it) } ?: deviceId}")
        refreshDevices()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val device = event.device
        if (device == null || !device.isController) return super.dispatchKeyEvent(event)
        if (event.repeatCount == 0) {
            val down = event.action == KeyEvent.ACTION_DOWN
            val key = KeyEvent.keyCodeToString(event.keyCode)
            log("${shortName(device)} ${if (down) "DOWN" else "UP  "} $key scan=${event.scanCode}")
            val held = pressedKeys.getOrPut(device.id) { linkedSetOf() }
            if (down) held += "$key(${event.scanCode})" else held -= "$key(${event.scanCode})"
            renderLive()
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return super.dispatchGenericMotionEvent(event)
        if (!device.isController) return super.dispatchGenericMotionEvent(event)
        val axes = device.motionRanges.map { it.axis }.distinct()
        liveAxes[device.id] = axes.joinToString(" ") {
            "${MotionEvent.axisToString(it).removePrefix("AXIS_")}=%+.2f".format(event.getAxisValue(it))
        }
        for (axis in axes) {
            val value = event.getAxisValue(axis)
            val key = device.id to axis
            val last = loggedAxes[key]
            if (last == null || abs(value - last) >= AXIS_LOG_STEP) {
                loggedAxes[key] = value
                if (last != null) log("${shortName(device)} ${MotionEvent.axisToString(axis)} = %+.3f".format(value))
            }
        }
        renderLive()
        return true
    }

    private fun renderLive() {
        val text = buildString {
            for (id in (liveAxes.keys + pressedKeys.keys).distinct()) {
                val name = InputDevice.getDevice(id)?.let { shortName(it) } ?: "#$id"
                appendLine("$name")
                pressedKeys[id]?.takeIf { it.isNotEmpty() }?.let { appendLine("  keys: ${it.joinToString()}") }
                liveAxes[id]?.let { appendLine("  $it") }
            }
        }
        liveView.text = text.ifEmpty { "(nothing held)" }
    }

    private fun refreshDevices() {
        bluetoothView.text = WiitarApp.pairedWiimotesSummary(this)
        devicesView.text = deviceListing()
    }

    private fun deviceListing(): String = buildString {
        val devices = inputManager.inputDeviceIds.toList().mapNotNull { InputDevice.getDevice(it) }
        // External devices (the Wiimote's) first; built-in ones after.
        for (d in devices.sortedByDescending { it.isController }) {
            appendLine(summary(d))
            appendLine("  sources: ${sourceNames(d.sources)}  keyboardType=${d.keyboardType}  controller#=${d.controllerNumber}")
            appendLine("  descriptor: ${d.descriptor}")
            val ranges = d.motionRanges.distinctBy { it.axis }
            if (ranges.isNotEmpty()) {
                appendLine("  axes:")
                ranges.forEach {
                    appendLine(
                        "    ${MotionEvent.axisToString(it.axis)} [%.2f..%.2f] flat=%.2f fuzz=%.2f"
                            .format(it.min, it.max, it.flat, it.fuzz),
                    )
                }
            }
            appendLine()
        }
    }

    private fun summary(d: InputDevice) =
        "#${d.id} \"${d.name}\" vendor=%04x product=%04x %s".format(
            d.vendorId,
            d.productId,
            if (d.isExternal) "external" else "built-in",
        )

    private fun shortName(d: InputDevice) = "${d.name}#${d.id}"

    private fun sourceNames(sources: Int): String = SOURCES.filter { (bits, _) -> sources and bits == bits }
        .joinToString("|") { it.second }
        .ifEmpty { "0x%08x".format(sources) }

    private fun report(): String = buildString {
        appendLine("Wiitar input report")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), kernel ${System.getProperty("os.version")}")
        appendLine(WiitarApp.pairedWiimotesSummary(this@InputDiagnosticsActivity))
        appendLine()
        appendLine("== Input devices ==")
        append(deviceListing())
        appendLine("== Log ==")
        append(DiagnosticLog.text())
    }

    private fun log(message: String) = DiagnosticLog.add("[input] $message")

    private companion object {
        const val AXIS_LOG_STEP = 0.1f

        val SOURCES = listOf(
            InputDevice.SOURCE_KEYBOARD to "KEYBOARD",
            InputDevice.SOURCE_DPAD to "DPAD",
            InputDevice.SOURCE_GAMEPAD to "GAMEPAD",
            InputDevice.SOURCE_JOYSTICK to "JOYSTICK",
            InputDevice.SOURCE_MOUSE to "MOUSE",
            InputDevice.SOURCE_TOUCHSCREEN to "TOUCHSCREEN",
            InputDevice.SOURCE_TOUCHPAD to "TOUCHPAD",
            InputDevice.SOURCE_STYLUS to "STYLUS",
            InputDevice.SOURCE_SENSOR to "SENSOR",
            InputDevice.SOURCE_ROTARY_ENCODER to "ROTARY",
        )
    }
}
