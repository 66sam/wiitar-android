/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.wiitar.protocol.WiimotePairing

/**
 * Wakes the app when a Wii Remote connects (ACL_CONNECTED is allowed for
 * manifest receivers), so the bridge can auto-start without opening Wiitar.
 * Starting the app process also runs BridgeClient.init, which auto-starts as
 * soon as Shizuku's binder arrives.
 */
class AutoStartReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission") // the broadcast is only delivered with BLUETOOTH_CONNECT granted
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = intent.bluetoothDevice() ?: return
        val name = runCatching { device.name }.getOrNull()
        if (WiimotePairing.isWiimoteName(name)) BridgeClient.onWiimoteConnected()
    }
}
