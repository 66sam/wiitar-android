/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.InputDevice
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Plain platform widgets on purpose: no AndroidX/Compose dependencies while the
// connection approach is still being proven on hardware.

/** Sets a scrolling vertical column as the content view and returns it. */
fun Activity.scrollingColumn(): LinearLayout {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
    }
    val scroll = ScrollView(this).apply {
        fitsSystemWindows = true
        addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    setContentView(scroll)
    return column
}

fun Activity.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun LinearLayout.heading(text: String): TextView = TextView(context).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    this@heading.addView(this)
}

fun LinearLayout.paragraph(text: String = ""): TextView = TextView(context).apply {
    this.text = text
    setTextIsSelectable(true)
    this@paragraph.addView(this)
}

fun LinearLayout.monospace(text: String = ""): TextView = paragraph(text).apply {
    typeface = Typeface.MONOSPACE
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
}

fun LinearLayout.button(text: String, onClick: () -> Unit): Button = Button(context).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
    this@button.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

/**
 * Devices the test screens listen to: anything external, plus gamepads and
 * joysticks (the bridge's virtual "Wiitar Guitar" may be classed as built-in).
 * Their keys are consumed so e.g. BUTTON_B doesn't fall back to BACK.
 */
val InputDevice.isController: Boolean
    get() = isExternal ||
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

fun Activity.copyToClipboard(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

/**
 * Timestamped in-memory log shared by the screens, mirrored to logcat under
 * the "wiitar" tag. The "Copy log" buttons put this on the clipboard so it can
 * be pasted into a bug report.
 */
object DiagnosticLog {
    private const val MAX_LINES = 1500
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun add(message: String) {
        Log.i("wiitar", message)
        lines.addLast("${format.format(Date())} $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
        listeners.toList().forEach { it() }
    }

    @Synchronized
    fun text(lastLines: Int = MAX_LINES): String = lines.toList().takeLast(lastLines).joinToString("\n")

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}
