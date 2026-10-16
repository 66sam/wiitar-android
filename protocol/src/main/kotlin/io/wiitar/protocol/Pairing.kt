/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.protocol

/**
 * Wiimotes use legacy Bluetooth PIN pairing with a 6-byte binary PIN:
 *  - discoverable via the 1+2 buttons: the Wiimote's own address, byte-reversed;
 *  - discoverable via the red SYNC button: the *host's* address, byte-reversed.
 *    The Wiimote then remembers the host and reconnects on any button press.
 *
 * Reference: https://wiibrew.org/wiki/Wiimote#Bluetooth_Pairing
 */
object WiimotePairing {
    /** What Android returns from BluetoothAdapter.getAddress() to apps without system privileges. */
    const val HIDDEN_ADDRESS = "02:00:00:00:00:00"

    private val ADDRESS = Regex("^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$")

    enum class Method { BUTTONS_1_2, SYNC_BUTTON }

    fun isValidAddress(address: String): Boolean = ADDRESS.matches(address.trim())

    /** Normalises "001f32aabbcc" / "00-1f-..." to "00:1F:32:AA:BB:CC". */
    fun normalizeAddress(address: String): String {
        require(isValidAddress(address)) { "not a Bluetooth address: $address" }
        return address.trim().filter { it.isLetterOrDigit() }.uppercase().chunked(2).joinToString(":")
    }

    /** "00:1F:32:AA:BB:CC" -> bytes CC BB AA 32 1F 00. */
    fun pinFromAddress(address: String): ByteArray =
        hexBytes(normalizeAddress(address).replace(":", "")).reversedArray()

    /** @param hostAddress required for [Method.SYNC_BUTTON]. */
    fun pinFor(method: Method, wiimoteAddress: String, hostAddress: String?): ByteArray = when (method) {
        Method.BUTTONS_1_2 -> pinFromAddress(wiimoteAddress)
        Method.SYNC_BUTTON -> pinFromAddress(requireNotNull(hostAddress) { "SYNC pairing needs this device's address" })
    }

    /** Wii Remote ("RVL-CNT-01"), Wii Remote Plus ("RVL-CNT-01-TR"), and other Nintendo RVL peripherals. */
    fun isWiimoteName(name: String?): Boolean = name?.startsWith("Nintendo RVL-") == true
}
