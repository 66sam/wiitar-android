/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView

/**
 * Walks through every guitar control one at a time and records which key
 * codes and axes each one produces, so the mapping is unambiguous. This is
 * the same "listen for the next input" capture the remapping UI will use.
 */
class GuidedTestActivity : Activity() {
    private class Step(val label: String, val instruction: String)

    private val steps = listOf(
        Step("Green fret", "Press and release the GREEN fret a couple of times."),
        Step("Red fret", "Press and release the RED fret."),
        Step("Yellow fret", "Press and release the YELLOW fret."),
        Step("Blue fret", "Press and release the BLUE fret."),
        Step("Orange fret", "Press and release the ORANGE fret."),
        Step("Strum up", "Strum UP a few times (flick the strum bar towards your face)."),
        Step("Strum down", "Strum DOWN a few times."),
        Step("Plus", "Press the + (plus / star power) button."),
        Step("Minus", "Press the − (minus) button."),
        Step("Whammy", "Push the whammy bar all the way, then let it return slowly."),
        Step("Tilt", "Hold the guitar normally, raise the neck straight up, then lower it again."),
        Step("Touch strip", "Slide a finger along the touch strip above the frets. Skip if your guitar has none."),
        Step("Stick", "Move the small analog stick in a full circle."),
        Step("Wii Remote", "Press A, B, 1, 2, Home and the D-pad on the Wii Remote itself."),
    )
    private val results = arrayOfNulls<String>(steps.size)
    private var index = 0
    private var capture = InputCapture()

    private lateinit var titleView: TextView
    private lateinit var instructionView: TextView
    private lateinit var seenView: TextView
    private lateinit var summaryView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = scrollingColumn()
        titleView = column.heading("")
        instructionView = column.paragraph()
        column.heading("Seen during this step")
        seenView = column.monospace()
        column.button("Next") { finishStep(skipped = false) }
        column.button("Skip (my guitar doesn't have this)") { finishStep(skipped = true) }
        column.button("Redo this step") { startStep() }
        column.heading("Results")
        column.button("Copy results") { copyToClipboard("wiitar guided test", report()) }
        summaryView = column.monospace()
        startStep()
    }

    private fun startStep() {
        capture = InputCapture()
        if (index >= steps.size) {
            titleView.text = "Done"
            instructionView.text = "Tap Copy results and send the text back."
            seenView.text = ""
        } else {
            val step = steps[index]
            titleView.text = "Step ${index + 1} of ${steps.size}: ${step.label}"
            instructionView.text = step.instruction + "\nThen tap Next."
            seenView.text = "(nothing yet)"
        }
        summaryView.text = resultsText()
    }

    private fun finishStep(skipped: Boolean) {
        if (index >= steps.size) return
        results[index] = if (skipped) "skipped" else capture.summary().ifEmpty { "NOTHING DETECTED" }
        DiagnosticLog.add("[test] ${steps[index].label}: ${results[index]!!.replace("\n", "; ")}")
        index++
        startStep()
    }

    private fun resultsText(): String = buildString {
        steps.forEachIndexed { i, step ->
            val result = results[i] ?: return@forEachIndexed
            appendLine("${step.label}:")
            result.lines().forEach { appendLine("  $it") }
        }
    }

    private fun report(): String = buildString {
        appendLine("Wiitar guided test")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), kernel ${System.getProperty("os.version")}")
        appendLine(WiitarApp.pairedWiimotesSummary(this@GuidedTestActivity))
        appendLine("External input devices:")
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { it.isExternal }.forEach {
            appendLine("  #${it.id} \"${it.name}\" %04x:%04x".format(it.vendorId, it.productId))
        }
        appendLine()
        append(resultsText())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val device = event.device
        if (device == null || !device.isExternal) return super.dispatchKeyEvent(event)
        capture.onKey(event)
        seenView.text = capture.summary().ifEmpty { "(nothing yet)" }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val device = event.device
        if (device == null || !device.isExternal) return super.dispatchGenericMotionEvent(event)
        capture.onMotion(event)
        seenView.text = capture.summary().ifEmpty { "(nothing yet)" }
        return true
    }
}

/**
 * Collects the keys pressed and the range each axis moved through while
 * active. Axes that moved less than [MIN_AXIS_TRAVEL] are treated as noise.
 */
class InputCapture {
    private val keys = LinkedHashMap<String, Int>()
    private val axes = LinkedHashMap<String, FloatArray>() // [first, min, max]

    fun onKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val name = "${deviceName(event.device)} ${KeyEvent.keyCodeToString(event.keyCode)} (scan ${event.scanCode})"
        keys[name] = (keys[name] ?: 0) + 1
    }

    fun onMotion(event: MotionEvent) {
        val device = event.device ?: return
        for (axis in device.motionRanges.map { it.axis }.distinct()) {
            val value = event.getAxisValue(axis)
            val name = "${deviceName(device)} ${MotionEvent.axisToString(axis)}"
            val range = axes.getOrPut(name) { floatArrayOf(value, value, value) }
            if (value < range[1]) range[1] = value
            if (value > range[2]) range[2] = value
        }
    }

    fun summary(): String = buildString {
        keys.forEach { (name, count) -> appendLine("key  $name ×$count") }
        axes.filterValues { it[2] - it[1] >= MIN_AXIS_TRAVEL }.forEach { (name, r) ->
            appendLine("axis $name %+.2f..%+.2f (started at %+.2f)".format(r[1], r[2], r[0]))
        }
    }.trimEnd()

    private fun deviceName(device: InputDevice?) = device?.let { "${it.name}#${it.id}" } ?: "?"

    private companion object {
        const val MIN_AXIS_TRAVEL = 0.15f
    }
}
