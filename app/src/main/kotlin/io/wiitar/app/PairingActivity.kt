/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import io.wiitar.protocol.WiimotePairing
import io.wiitar.protocol.toHex

/**
 * Discovers Wii Remotes and pairs them through the system Bluetooth stack,
 * answering the legacy PIN request with the binary PIN the Wiimote expects.
 * Once bonded, Android's HID host (and the kernel's hid-wiimote driver, if
 * present) take over the connection.
 */
@SuppressLint("MissingPermission") // every Bluetooth call is behind hasPermissions()
class PairingActivity : Activity() {
    private var adapter: BluetoothAdapter? = null
    private lateinit var methodSync: RadioButton
    private lateinit var hostAddressField: EditText
    private lateinit var deviceList: LinearLayout
    private lateinit var logView: TextView

    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val names = HashMap<String, String>()
    private var target: BluetoothDevice? = null

    private val logListener: () -> Unit = { runOnUiThread { logView.text = DiagnosticLog.text(lastLines = 60) } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.bluetoothDevice()
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> log("Scanning…")
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> log("Scan finished")
                BluetoothDevice.ACTION_FOUND -> if (device != null) {
                    onFound(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME))
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> if (device != null) {
                    onFound(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME))
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> if (device != null) {
                    if (onPairingRequest(device, intent) && isOrderedBroadcast) abortBroadcast()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (device != null) {
                    onBondStateChanged(
                        device,
                        intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1),
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1),
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val prefs = getSharedPreferences("pairing", MODE_PRIVATE)

        val column = scrollingColumn()
        column.heading("1. Choose how you'll put the Wii Remote in pairing mode")
        val group = RadioGroup(this)
        val method12 = RadioButton(this).apply {
            id = 1
            text = "Hold the 1 and 2 buttons (simplest; press 1+2 again each time you connect)"
        }
        methodSync = RadioButton(this).apply {
            id = 2
            text = "Press the red SYNC button (remembers this device and reconnects on any button press)"
        }
        group.addView(method12)
        group.addView(methodSync)
        column.addView(group)
        group.check(if (prefs.getBoolean("sync", false)) 2 else 1)

        column.paragraph(
            "SYNC pairing uses this device's Bluetooth address as the PIN, and Android doesn't let apps read it. " +
                "Find it under Settings → About phone → Status (\"Bluetooth address\") and enter it here:",
        )
        hostAddressField = EditText(this).apply {
            hint = "e.g. 12:34:56:78:9A:BC"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(prefs.getString("hostAddress", ""))
        }
        column.addView(hostAddressField)
        group.setOnCheckedChangeListener { _, _ -> savePrefs() }

        column.heading("2. Scan")
        column.paragraph(
            "Plug the guitar into the Wii Remote, put it in pairing mode (LEDs blink for about 20 seconds), " +
                "then tap Scan. Tap the Wii Remote when it appears. If Android shows a PIN dialog, ignore it; " +
                "this app supplies the PIN.",
        )
        column.button("Scan") { startScan() }
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(deviceList)

        column.heading("3. Connect")
        column.paragraph(
            "Pairing doesn't connect the controller by itself.\n" +
                "• SYNC-paired: press any button on the Wii Remote; it connects to this device on its own.\n" +
                "• 1+2-paired: hold 1+2 until the LEDs blink, then tap the Wii Remote in Android's Bluetooth settings.\n" +
                "The log shows \"HID input profile … connected\" when it worked.",
        )
        column.button("Open Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }

        column.heading("Log")
        column.button("Copy log") { copyToClipboard("wiitar pairing log", DiagnosticLog.text()) }
        logView = column.monospace()

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            // Answer the PIN request before the system Settings dialog does.
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        DiagnosticLog.addListener(logListener)
        logListener()
        if (adapter == null) log("This device has no Bluetooth adapter.")
        if (!hasPermissions()) requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS) else showBonded()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        DiagnosticLog.removeListener(logListener)
        if (hasPermissions()) adapter?.cancelDiscovery()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (hasPermissions()) showBonded() else log("Bluetooth permission denied; can't scan or pair.")
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions() =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun showBonded() {
        val bonded = adapter?.bondedDevices.orEmpty().filter { WiimotePairing.isWiimoteName(it.name) }
        if (bonded.isNotEmpty()) {
            log("Already paired: " + bonded.joinToString { describe(it) })
            log("To pair one again, first \"Forget\" it in Android's Bluetooth settings.")
        }
        bonded.forEach { onFound(it, it.name) }
    }

    private fun startScan() {
        val adapter = adapter ?: return log("No Bluetooth adapter.")
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is off; asking to turn it on.")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        savePrefs()
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        val started = adapter.startDiscovery()
        if (!started) log("startDiscovery() failed. Is Location turned on? (needed on some devices)")
    }

    private fun onFound(device: BluetoothDevice, name: String?) {
        if (name != null) names[device.address] = name
        val isNew = found.put(device.address, device) == null
        if (isNew) log("Found ${describe(device)} class=${device.bluetoothClass}")
        renderDevices()
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        // Wiimotes first; other devices are listed too in case the name hasn't resolved yet.
        val sorted = found.values.sortedByDescending { isWiimote(it) }
        for (device in sorted) {
            val star = if (isWiimote(device)) "★ " else ""
            val bonded = if (device.bondState == BluetoothDevice.BOND_BONDED) "  [paired]" else ""
            deviceList.button("$star${nameOf(device) ?: "(no name yet)"}  ${device.address}$bonded") { pair(device) }
        }
    }

    private fun pair(device: BluetoothDevice) {
        adapter?.cancelDiscovery()
        if (methodSync.isChecked && hostAddress() == null) {
            log("SYNC pairing needs this device's Bluetooth address; enter it above first.")
            return
        }
        target = device
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            log("${describe(device)} is already paired. \"Forget\" it in Bluetooth settings to pair again.")
            return
        }
        log("Pairing with ${describe(device)} using ${method()}…")
        val started = device.createBond()
        if (!started) log("createBond() returned false. Is the Wii Remote still blinking? Put it in pairing mode and tap again.")
    }

    /** @return true if we answered the request (so the system dialog can be suppressed). */
    private fun onPairingRequest(device: BluetoothDevice, intent: Intent): Boolean {
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        log("Pairing request from ${describe(device)}, variant=$variant")
        if (device.address != target?.address && !isWiimote(device)) {
            log("Not a Wii Remote we're pairing; leaving it to the system.")
            return false
        }
        if (variant != BluetoothDevice.PAIRING_VARIANT_PIN) {
            log("Expected a PIN request (variant ${BluetoothDevice.PAIRING_VARIANT_PIN}); can't answer variant $variant.")
            return false
        }
        val pin = try {
            WiimotePairing.pinFor(method(), device.address, hostAddress())
        } catch (e: IllegalArgumentException) {
            log("Can't build PIN: ${e.message}")
            return false
        }
        val accepted = device.setPin(pin)
        log("setPin(${pin.toHex()}) → $accepted")
        return accepted
    }

    private fun onBondStateChanged(device: BluetoothDevice, previous: Int, state: Int) {
        log("Bond state ${describe(device)}: ${bondName(previous)} → ${bondName(state)}")
        if (state == BluetoothDevice.BOND_BONDED && isWiimote(device)) {
            log("Paired. Now connect it (step 3), then open Input diagnostics.")
        } else if (previous == BluetoothDevice.BOND_BONDING && state == BluetoothDevice.BOND_NONE) {
            log("Pairing failed. Check the method above matches the button you pressed, then try again.")
        }
        renderDevices()
    }

    private fun method() =
        if (methodSync.isChecked) WiimotePairing.Method.SYNC_BUTTON else WiimotePairing.Method.BUTTONS_1_2

    private fun hostAddress(): String? {
        val typed = hostAddressField.text.toString().trim()
        if (typed.isNotEmpty()) {
            return if (WiimotePairing.isValidAddress(typed)) WiimotePairing.normalizeAddress(typed) else null
        }
        val reported = adapter?.address
        return reported?.takeIf { it != WiimotePairing.HIDDEN_ADDRESS && WiimotePairing.isValidAddress(it) }
    }

    private fun savePrefs() {
        getSharedPreferences("pairing", MODE_PRIVATE).edit()
            .putBoolean("sync", methodSync.isChecked)
            .putString("hostAddress", hostAddressField.text.toString().trim())
            .apply()
    }

    private fun isWiimote(device: BluetoothDevice) = WiimotePairing.isWiimoteName(nameOf(device))

    private fun nameOf(device: BluetoothDevice): String? = names[device.address] ?: device.name

    private fun describe(device: BluetoothDevice) = "${nameOf(device) ?: "?"} (${device.address})"

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_NONE -> "none"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_BONDED -> "bonded"
        else -> state.toString()
    }

    private fun log(message: String) = DiagnosticLog.add("[pair] $message")

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
