/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import io.wiitar.bridge.BridgeMapping
import io.wiitar.bridge.ButtonLayout
import io.wiitar.bridge.TiltCalibration
import io.wiitar.bridge.Vec3
import io.wiitar.bridge.VirtualGamepad
import rikka.shizuku.Shizuku

/**
 * Starts/stops the guitar bridge (see [BridgeService]) and shows its live
 * status. The bridge keeps running after this screen is closed.
 */
class BridgeActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var tiltAxisGroup: RadioGroup
    private lateinit var tiltInvert: CheckBox
    private lateinit var calibrationView: TextView
    private var capturedRest: Vec3? = null
    private lateinit var layoutWarning: TextView
    private val layoutSpinners = HashMap<ButtonLayout.Input, Spinner>()

    /** Spinner choices: every gamepad button, plus "none" for the tilt button. */
    private fun choices(input: ButtonLayout.Input): List<String> =
        VirtualGamepad.Button.entries.map { it.name } + if (input == ButtonLayout.Input.TILT) listOf(ButtonLayout.NONE) else emptyList()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var service: IBridgeService? = null
    private var startWhenConnected = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, BridgeService::class.java.name))
            .daemon(true) // keep running while a game is in the foreground
            .processNameSuffix("bridge")
            .tag("bridge")
            .debuggable(false)
            .version(SERVICE_VERSION)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val s = IBridgeService.Stub.asInterface(binder)
            service = s
            if (startWhenConnected) {
                startWhenConnected = false
                val axis = tiltAxisGroup.checkedRadioButtonId
                val invert = tiltInvert.isChecked
                val calibration = savedCalibration()
                val layout = savedLayout()
                background { s.start(axis, invert, calibration, layout) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == REQUEST_SHIZUKU && result == PackageManager.PERMISSION_GRANTED) bind()
    }

    private val refresh = object : Runnable {
        override fun run() {
            val s = service
            if (s == null) {
                statusView.text = "Bridge not connected.\n${shizukuState()}"
            } else {
                background {
                    val text = try {
                        s.status()
                    } catch (e: Exception) {
                        "Bridge unreachable: $e"
                    }
                    runOnUiThread { statusView.text = text }
                }
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
        val column = scrollingColumn()
        column.paragraph(
            "The bridge reads the guitar (including tilt) and presents it as a standard gamepad called " +
                "\"Wiitar Guitar\". Bind that controller in Clone Hero / YARG, not \"Nintendo Wii Remote Guitar\".\n\n" +
                "Needs Shizuku running (see the probe screen). The bridge keeps running in the background until you stop it, " +
                "the Wii Remote disconnects, or the device reboots.\n\n" +
                "Layout: Green A · Red X · Yellow Y · Blue LB · Orange RB · strum D-pad up/down · plus Start · minus Select · " +
                "tilt right stick Y (RZ), and right-stick click when tilted past about 60% · whammy right stick X (Z), " +
                "when the guitar exposes it. If Clone Hero lists it as player 2, bind it there.",
        )
        column.heading("Buttons")
        column.paragraph(
            "What each guitar control presses on \"Wiitar Guitar\". Changes apply immediately; rebind in the game afterwards. " +
                "Avoid B (Android turns an unhandled B into Back).",
        )
        layoutWarning = column.paragraph()
        for (input in ButtonLayout.Input.entries) addLayoutRow(column, input)
        column.button("Reset buttons to default") { applyLayout(BridgeMapping(), rebuild = true) }

        column.heading("Tilt calibration")
        column.paragraph(
            "With the bridge running: hold the guitar the way you play and tap 1, then raise the neck " +
                "as you would for star power and tap 2. Tilt is then measured along that motion, " +
                "however the Wii Remote sits in your guitar.",
        )
        calibrationView = column.paragraph()
        column.button("1. Holding normally — capture") { captureRest() }
        column.button("2. Neck raised — capture") { captureUp() }
        column.button("Clear calibration") { applyCalibration(null) }
        renderCalibration()

        column.heading("Tilt axis (only used without calibration)")
        column.paragraph("Watch the Tilt line below while raising the neck; pick the axis whose angle goes up. Applies on next start.")
        tiltAxisGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("X", "Y", "Z").forEachIndexed { i, label ->
            tiltAxisGroup.addView(RadioButton(this).apply { id = i; text = label })
        }
        tiltAxisGroup.check(prefs.getInt("tiltAxis", 1))
        column.addView(tiltAxisGroup)
        tiltInvert = CheckBox(this).apply { text = "Invert tilt"; isChecked = prefs.getBoolean("tiltInvert", false) }
        column.addView(tiltInvert)

        column.button("Start bridge") { start() }
        column.button("Stop bridge") { stop() }
        column.button("Copy status") { copyToClipboard("wiitar bridge", statusView.text.toString()) }
        statusView = column.monospace()

        Shizuku.addRequestPermissionResultListener(permissionListener)
        // Reattach to a bridge left running from earlier, without starting a new one.
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        getSharedPreferences("bridge", MODE_PRIVATE).edit()
            .putInt("tiltAxis", tiltAxisGroup.checkedRadioButtonId)
            .putBoolean("tiltInvert", tiltInvert.isChecked)
            .apply()
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun start() {
        val s = service
        if (s != null) {
            val axis = tiltAxisGroup.checkedRadioButtonId
            val invert = tiltInvert.isChecked
            val calibration = savedCalibration()
            val layout = savedLayout()
            background { s.start(axis, invert, calibration, layout) }
            return
        }
        startWhenConnected = true
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> statusView.text = shizukuState()
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Shizuku.requestPermission(REQUEST_SHIZUKU)
            else -> bind()
        }
    }

    private fun savedLayout(): String =
        getSharedPreferences("bridge", MODE_PRIVATE).getString("buttonLayout", "").orEmpty()

    private fun addLayoutRow(column: LinearLayout, input: ButtonLayout.Input) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = input.label }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val options = choices(input)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        row.addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(row)
        layoutSpinners[input] = spinner
        val current = ButtonLayout.get(ButtonLayout.decode(savedLayout()), input)?.name ?: ButtonLayout.NONE
        spinner.setSelection(options.indexOf(current).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val button = VirtualGamepad.Button.entries.firstOrNull { it.name == options[position] }
                val mapping = ButtonLayout.decode(savedLayout())
                if (ButtonLayout.get(mapping, input) != button) applyLayout(ButtonLayout.set(mapping, input, button), rebuild = false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        renderLayoutWarning()
    }

    private fun applyLayout(mapping: BridgeMapping, rebuild: Boolean) {
        val encoded = ButtonLayout.encode(mapping)
        getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("buttonLayout", encoded).apply()
        DiagnosticLog.add("[bridge] buttons: $encoded")
        if (rebuild) {
            for ((input, spinner) in layoutSpinners) {
                val options = choices(input)
                spinner.setSelection(options.indexOf(ButtonLayout.get(mapping, input)?.name ?: ButtonLayout.NONE).coerceAtLeast(0), false)
            }
        }
        renderLayoutWarning()
        service?.let { s -> background { s.setButtonLayout(encoded) } }
    }

    private fun renderLayoutWarning() {
        val mapping = ButtonLayout.decode(savedLayout())
        val dupes = ButtonLayout.duplicates(mapping)
        val usesB = ButtonLayout.Input.entries.any { ButtonLayout.get(mapping, it) == VirtualGamepad.Button.B }
        layoutWarning.text = buildString {
            if (dupes.isNotEmpty()) append("⚠ Used twice: ${dupes.joinToString()}. ")
            if (usesB) append("⚠ B may act as Back in games.")
        }
    }

    private fun savedCalibration(): String =
        getSharedPreferences("bridge", MODE_PRIVATE).getString("tiltCalibration", "").orEmpty()

    private fun renderCalibration() {
        val c = TiltCalibration.decode(savedCalibration())
        calibrationView.text = when {
            capturedRest != null -> "Step 1 captured. Now raise the neck and tap 2."
            c == null -> "Not calibrated (using the fixed axis below)."
            !c.usable -> "Calibration unusable: the two poses were almost the same. Capture again with a bigger tilt."
            else -> "Calibrated ✓"
        }
    }

    /** Reads the bridge's current gravity vector off the main thread, then hands it to [then] on the main thread. */
    private fun withGravity(then: (Vec3) -> Unit) {
        val s = service ?: run {
            calibrationView.text = "Start the bridge first."
            return
        }
        background {
            val g = Vec3.decode(s.gravity())
            runOnUiThread {
                if (g == null) {
                    calibrationView.text = "No accelerometer data yet. Is the guitar connected?"
                } else {
                    then(g)
                }
            }
        }
    }

    private fun captureRest() = withGravity {
        capturedRest = it
        renderCalibration()
    }

    private fun captureUp() = withGravity { up ->
        val rest = capturedRest ?: run {
            calibrationView.text = "Do step 1 first."
            return@withGravity
        }
        capturedRest = null
        applyCalibration(TiltCalibration(rest, up))
    }

    private fun applyCalibration(calibration: TiltCalibration?) {
        capturedRest = null
        val encoded = calibration?.encode().orEmpty()
        getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("tiltCalibration", encoded).apply()
        DiagnosticLog.add("[bridge] tilt calibration: ${encoded.ifEmpty { "cleared" }}")
        renderCalibration()
        service?.let { s -> background { s.setTiltCalibration(encoded) } }
    }

    private fun stop() {
        startWhenConnected = false
        try {
            // remove = true calls BridgeService.destroy(), which removes the virtual gamepad and exits.
            Shizuku.unbindUserService(args, connection, true)
        } catch (e: Exception) {
            DiagnosticLog.add("[bridge] stop failed: $e")
        }
        service = null
    }

    private fun bind() {
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            statusView.text = "Couldn't start the bridge service: $e"
        }
    }

    private fun shizukuState(): String = when {
        !Shizuku.pingBinder() -> "Shizuku is not running. Start it via Wireless debugging."
        Shizuku.isPreV11() -> "This Shizuku version is too old; please update it."
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku is running; tap Start bridge and allow Wiitar."
        else -> "Shizuku ready. Tap Start bridge."
    }

    private fun background(block: () -> Unit) = Thread(Runnable {
        try {
            block()
        } catch (e: Exception) {
            DiagnosticLog.add("[bridge] $e")
        }
    }, "wiitar-bridge-ui").start()

    private companion object {
        const val REQUEST_SHIZUKU = 43
        const val SERVICE_VERSION = 5 // bump whenever BridgeService changes so Shizuku restarts the running daemon
        const val REFRESH_MS = 300L
    }
}
