/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.protocol

/** Extension controller register map (control-register address space). */
object ExtensionRegisters {
    /** Writing 0x55 here, then 0x00 to [INIT_2], enables the extension with encryption off. */
    const val INIT_1 = 0xA400F0
    const val INIT_2 = 0xA400FB

    /** 6-byte identifier at 0xA400FA-0xA400FF; the last two bytes alone (0xA400FE) are ambiguous. */
    const val ID = 0xA400FA
    const val ID_SIZE = 6

    /** 16-byte calibration block (0xA40020-0xA4002F). */
    const val CALIBRATION = 0xA40020
    const val CALIBRATION_SIZE = 16
}

/** What the extension does. [GUITAR] is the only family we decode to gamepad input today. */
enum class ExtensionFamily { NONE, GUITAR, DRUMS, TURNTABLE, NUNCHUK, CLASSIC, MOTION_PLUS, OTHER }

/**
 * Known extension IDs (bytes read from 0xA400FA, unencrypted).
 *
 * To add support for a new guitar: read its ID on the diagnostics screen and
 * add an entry here. If it reports the standard guitar data format, give it
 * [ExtensionFamily.GUITAR] and the existing decoder will handle it.
 *
 * Reference: https://wiibrew.org/wiki/Wiimote/Extension_Controllers
 */
enum class ExtensionType(val id: String?, val family: ExtensionFamily, val displayName: String) {
    // Guitar Hero 3 and World Tour / GH5 / Band Hero guitars all report this ID;
    // World Tour-style touch strips are detected from the data (see GuitarDecoder).
    GUITAR("0000a4200103", ExtensionFamily.GUITAR, "Guitar Hero guitar"),
    DRUMS("0100a4200103", ExtensionFamily.DRUMS, "Guitar Hero World Tour drums"),
    TURNTABLE("0300a4200103", ExtensionFamily.TURNTABLE, "DJ Hero turntable"),
    NUNCHUK("0000a4200000", ExtensionFamily.NUNCHUK, "Nunchuk"),
    CLASSIC("0000a4200101", ExtensionFamily.CLASSIC, "Classic Controller"),
    CLASSIC_PRO("0100a4200101", ExtensionFamily.CLASSIC, "Classic Controller Pro"),
    TAIKO("0000a4200111", ExtensionFamily.OTHER, "Taiko drum (TaTaCon)"),
    UDRAW("ff00a4200013", ExtensionFamily.OTHER, "uDraw tablet"),
    MOTION_PLUS("0000a4200405", ExtensionFamily.MOTION_PLUS, "Wii MotionPlus"),
    UNKNOWN(null, ExtensionFamily.OTHER, "Unknown extension"),
    ;

    companion object {
        /**
         * Matches an exact 6-byte ID. Returns [UNKNOWN] for anything not in
         * the table, including the all-0x00 / all-0xFF patterns a partially
         * inserted plug produces.
         */
        fun fromId(id: ByteArray): ExtensionType {
            val hex = id.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            return entries.firstOrNull { it.id == hex } ?: UNKNOWN
        }

        /** True for the patterns returned while the plug is half-inserted or still settling. */
        fun isNotReady(id: ByteArray): Boolean =
            id.all { it == 0x00.toByte() } || id.all { it == 0xFF.toByte() }
    }
}

/**
 * The raw 16-byte calibration block from 0xA40020, kept verbatim for diagnostics.
 *
 * Nunchuk/Classic blocks end in two checksum bytes (sum of bytes 0-13 plus
 * 0x55 and plus 0xAA). Guitars are not documented to use this block, so the
 * guitar decoder calibrates its analog inputs from observed values instead and
 * only records this block; [checksumValid] tells you whether it looks meaningful.
 */
class ExtensionCalibration(val raw: ByteArray) {
    val checksumValid: Boolean
        get() {
            if (raw.size < 16) return false
            val sum = (0 until 14).sumOf { raw.u8(it) }
            return raw.u8(14) == (sum + 0x55) and 0xFF && raw.u8(15) == (sum + 0xAA) and 0xFF
        }

    override fun toString() = "ExtensionCalibration(${raw.toHex()}, checksumValid=$checksumValid)"
}
