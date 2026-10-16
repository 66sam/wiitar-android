/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.wiitar.bridge.BridgeConfig
import io.wiitar.bridge.Profiles
import io.wiitar.bridge.TiltCalibration
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * App-side handle on the bridge: Shizuku state, the connection to
 * [BridgeService], and the saved [BridgeConfig]. Service calls go through
 * [call], off the main thread.
 */
object BridgeClient {
    /** Bump whenever BridgeService or its AIDL changes, so Shizuku replaces a running old daemon. */
    private const val SERVICE_VERSION = 12
    private const val REQUEST_SHIZUKU = 44
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class ShizukuStatus { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    private lateinit var app: Context
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "wiitar-bridge-client") }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var service: IBridgeService? = null
        private set

    /** True once the bridge has been started (and not stopped) in the service we're attached to. */
    @Volatile
    var running = false
        private set

    private var startWhenConnected = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, BridgeService::class.java.name))
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
            call {
                if (startWhenConnected) {
                    startWhenConnected = false
                    s.start(config().encode(), Profiles.encode(profiles()))
                }
                running = s.status().startsWith("Bridge running")
                notifyChanged()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            running = false
            notifyChanged()
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky {
            // Reattach to a bridge left running earlier; with auto-start, also start one.
            if (shizukuState() == ShizukuStatus.READY) {
                if (autoStart) startWhenConnected = true
                bind()
            }
            notifyChanged()
        }
        Shizuku.addBinderDeadListener { notifyChanged() }
        Shizuku.addRequestPermissionResultListener { requestCode, result ->
            if (requestCode == REQUEST_SHIZUKU && result == PackageManager.PERMISSION_GRANTED) bind()
            notifyChanged()
        }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)
    private fun notifyChanged() = listeners.forEach { it() }

    fun shizukuState(): ShizukuStatus = when {
        !isShizukuInstalled() -> ShizukuStatus.NOT_INSTALLED
        !Shizuku.pingBinder() || Shizuku.isPreV11() -> ShizukuStatus.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NEEDS_PERMISSION
        else -> ShizukuStatus.READY
    }

    private fun isShizukuInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        // Also true when Shizuku is reachable but package visibility hides it.
        Shizuku.pingBinder()
    }

    fun openShizuku(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_SHIZUKU)
    }

    fun start() {
        val s = service
        if (s != null) {
            call {
                s.start(config().encode(), Profiles.encode(profiles()))
                running = true
                notifyChanged()
            }
            return
        }
        startWhenConnected = true
        when (shizukuState()) {
            ShizukuStatus.READY -> bind()
            ShizukuStatus.NEEDS_PERMISSION -> requestPermission()
            else -> Unit
        }
    }

    fun stop() {
        startWhenConnected = false
        try {
            // remove = true calls BridgeService.destroy(): virtual gamepads go away and grabs are released.
            Shizuku.unbindUserService(args, connection, true)
        } catch (e: Exception) {
            DiagnosticLog.add("[bridge] stop failed: $e")
        }
        service = null
        running = false
        notifyChanged()
    }

    private fun bind() {
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            DiagnosticLog.add("[bridge] couldn't start the bridge service: $e")
        }
    }

    /** Runs [block] against the service off the main thread; a dead service just logs. */
    fun call(block: () -> Unit) = executor.execute {
        try {
            block()
        } catch (e: Exception) {
            DiagnosticLog.add("[bridge] $e")
        }
    }

    private val prefs get() = app.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun config(): BridgeConfig {
        prefs.getString("config", null)?.let { return BridgeConfig.decode(it) }
        // Settings saved by earlier versions, before BridgeConfig.
        return BridgeConfig(
            buttons = prefs.getString("buttonLayout", "").orEmpty(),
            tiltCalibration = TiltCalibration.decode(prefs.getString("tiltCalibration", null)),
            hideGuitar = prefs.getBoolean("hideGuitar", true),
        )
    }

    /** Per-guitar settings, keyed by guitar id (the Wii Remote's Bluetooth address). */
    fun profiles(): Map<String, BridgeConfig> = Profiles.decode(prefs.getString("profiles", null))

    /** The settings guitar [id] uses: its own profile, or the shared ones. */
    fun configFor(id: String?): BridgeConfig = id?.let { profiles()[it] } ?: config()

    fun hasProfile(id: String?): Boolean = id != null && id in profiles()

    /**
     * Saves a change and applies it to a running bridge immediately. With [id], the change
     * goes to that guitar's own profile (created from the shared settings on first change).
     */
    fun updateConfig(id: String? = null, change: (BridgeConfig) -> BridgeConfig) {
        if (id == null) {
            prefs.edit().putString("config", change(config()).encode()).apply()
        } else {
            val profiles = profiles()
            val updated = profiles + (id to change(profiles[id] ?: config()))
            prefs.edit().putString("profiles", Profiles.encode(updated)).apply()
        }
        push()
    }

    /** Drops guitar [id]'s own settings so it uses the shared ones again. */
    fun clearProfile(id: String) {
        prefs.edit().putString("profiles", Profiles.encode(profiles() - id)).apply()
        push()
    }

    private fun push() {
        val config = config().encode()
        val profiles = Profiles.encode(profiles())
        service?.let { s -> call { s.configure(config, profiles) } }
        notifyChanged()
    }

    /** Start the bridge by itself whenever Shizuku is available (app start, Shizuku start, guitar connect). */
    var autoStart: Boolean
        get() = prefs.getBoolean("autoStart", true)
        set(value) {
            prefs.edit().putBoolean("autoStart", value).apply()
            notifyChanged()
        }

    /** Called when a Wii Remote connects: starts the bridge if auto-start is on and it isn't running. */
    fun onWiimoteConnected() {
        if (autoStart && !running && shizukuState() == ShizukuStatus.READY) start()
    }
}
