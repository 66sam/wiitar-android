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
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Starts/stops the guitar bridge (see [BridgeService]) and shows its live
 * status. The bridge keeps running after this screen is closed.
 */
class BridgeActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var tiltAxisGroup: RadioGroup
    private lateinit var tiltInvert: CheckBox
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
                background { s.start(axis, invert) }
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
                "Default layout: Green A · Red B · Yellow Y · Blue X · Orange LB · strum D-pad up/down · " +
                "plus Start · minus Select · whammy right stick X (Z) · tilt right stick Y (RZ) and RB when tilted.",
        )
        column.heading("Tilt axis")
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
            background { s.start(axis, invert) }
            return
        }
        startWhenConnected = true
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> statusView.text = shizukuState()
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Shizuku.requestPermission(REQUEST_SHIZUKU)
            else -> bind()
        }
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
        const val SERVICE_VERSION = 1
        const val REFRESH_MS = 300L
    }
}
