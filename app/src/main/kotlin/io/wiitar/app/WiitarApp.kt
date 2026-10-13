/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.wiitar.protocol.WiimotePairing

/**
 * Logs Bluetooth link and HID-profile changes for Wii Remotes for as long as
 * the app runs, whichever screen is open. Bonding alone doesn't open the HID
 * channels; these lines show whether Android's HID host actually connected.
 */
class WiitarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_HID_HOST_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(BluetoothEventLogger, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(BluetoothEventLogger, filter)
        }
    }

    @SuppressLint("MissingPermission") // name lookups are guarded by canConnect()
    private object BluetoothEventLogger : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.bluetoothDevice() ?: return
            val name = if (canConnect(context)) device.name else null
            if (!WiimotePairing.isWiimoteName(name)) return
            val who = "$name (${device.address})"
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> DiagnosticLog.add("[bt] link up: $who")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> DiagnosticLog.add("[bt] link down: $who")
                ACTION_HID_HOST_CONNECTION_STATE_CHANGED -> {
                    val from = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                    val to = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    DiagnosticLog.add("[bt] HID input profile $who: ${stateName(from)} → ${stateName(to)}")
                }
            }
        }
    }

    companion object {
        /** BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED; the class is a system API, the broadcast isn't. */
        const val ACTION_HID_HOST_CONNECTION_STATE_CHANGED =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED"

        /** BluetoothProfile.HID_HOST, hidden from the public SDK. */
        private const val PROFILE_HID_HOST = 4

        fun canConnect(context: Context) = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun stateName(state: Int) = when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
            BluetoothProfile.STATE_CONNECTING -> "connecting"
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
            else -> state.toString()
        }

        /** One line per paired Wii Remote with its link and HID state, for reports. */
        @SuppressLint("MissingPermission")
        fun pairedWiimotesSummary(context: Context): String {
            if (!canConnect(context)) return "paired Wii Remotes: (Bluetooth permission not granted)"
            val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return "paired Wii Remotes: (no Bluetooth adapter)"
            val wiimotes = adapter.bondedDevices.orEmpty().filter { WiimotePairing.isWiimoteName(it.name) }
            val hid = try {
                stateName(adapter.getProfileConnectionState(PROFILE_HID_HOST))
            } catch (e: Exception) {
                "unknown (${e.javaClass.simpleName})"
            }
            return "HID input profile (any device): $hid\npaired Wii Remotes: " +
                wiimotes.joinToString { "${it.name} (${it.address})" }.ifEmpty { "none" }
        }
    }
}

fun Intent.bluetoothDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
