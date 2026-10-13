/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.protocol

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** "a1 30 00 00" style hex dump, used by diagnostics and log lines. */
fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

/** Parses "a1 30 00 00" / "a1300000" style hex, for tests and pasted diagnostics captures. */
fun hexBytes(hex: String): ByteArray {
    val clean = hex.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd-length hex string" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
