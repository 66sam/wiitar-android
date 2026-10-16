/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.protocol

/**
 * Builders for Wiimote output reports (host -> Wiimote).
 *
 * Every builder returns the report starting with its report ID and *without*
 * the 0xA2 HID transaction header. Transports that talk raw L2CAP must prefix
 * 0xA2 (see [HidFraming.wrapOutput]); hidraw writes take the bytes as-is.
 *
 * Reference: https://wiibrew.org/wiki/Wiimote#Output_Reports
 */
object OutputReports {
    const val ID_LEDS = 0x11
    const val ID_REPORT_MODE = 0x12
    const val ID_STATUS_REQUEST = 0x15
    const val ID_WRITE_MEMORY = 0x16
    const val ID_READ_MEMORY = 0x17

    /** Address-space flag: 0x04 selects control registers, 0x00 selects EEPROM. */
    private const val SPACE_REGISTER = 0x04
    private const val SPACE_EEPROM = 0x00

    /** Bit 2 of the report-mode flags byte: send reports continuously, not only on change. */
    private const val CONTINUOUS = 0x04

    /** @param mask bit 0 = LED 1 ... bit 3 = LED 4 */
    fun setLeds(mask: Int): ByteArray = bytesOf(ID_LEDS, (mask and 0x0F) shl 4)

    /** LED pattern for a zero-based player slot: one LED for slots 0-3, then pairs. */
    fun playerLedMask(slot: Int): Int = when (slot) {
        in 0..3 -> 1 shl slot
        4 -> 0b1001
        5 -> 0b1010
        6 -> 0b1100
        else -> 0b1111
    }

    fun setReportMode(mode: ReportMode, continuous: Boolean = true): ByteArray =
        bytesOf(ID_REPORT_MODE, if (continuous) CONTINUOUS else 0x00, mode.id)

    fun requestStatus(): ByteArray = bytesOf(ID_STATUS_REQUEST, 0x00)

    fun writeRegister(address: Int, data: ByteArray): ByteArray = write(SPACE_REGISTER, address, data)

    fun readRegister(address: Int, size: Int): ByteArray = read(SPACE_REGISTER, address, size)

    fun readEeprom(address: Int, size: Int): ByteArray = read(SPACE_EEPROM, address, size)

    private fun write(space: Int, address: Int, data: ByteArray): ByteArray {
        require(data.size in 1..16) { "write size must be 1..16 bytes, was ${data.size}" }
        val out = ByteArray(22)
        out[0] = ID_WRITE_MEMORY.toByte()
        out[1] = space.toByte()
        putAddress(out, 2, address)
        out[5] = data.size.toByte()
        data.copyInto(out, 6)
        return out
    }

    private fun read(space: Int, address: Int, size: Int): ByteArray {
        require(size in 1..0xFFFF) { "read size out of range: $size" }
        val out = ByteArray(7)
        out[0] = ID_READ_MEMORY.toByte()
        out[1] = space.toByte()
        putAddress(out, 2, address)
        out[5] = (size shr 8).toByte()
        out[6] = size.toByte()
        return out
    }

    private fun putAddress(out: ByteArray, at: Int, address: Int) {
        out[at] = (address shr 16).toByte()
        out[at + 1] = (address shr 8).toByte()
        out[at + 2] = address.toByte()
    }
}

/** Framing for transports that carry raw HID-over-L2CAP data (interrupt channel, PSM 0x13). */
object HidFraming {
    const val OUTPUT_HEADER = 0xA2
    const val INPUT_HEADER = 0xA1

    fun wrapOutput(report: ByteArray): ByteArray = byteArrayOf(OUTPUT_HEADER.toByte()) + report

    /** Strips a leading 0xA1 header if present; hidraw data has none. */
    fun unwrapInput(packet: ByteArray): ByteArray =
        if (packet.isNotEmpty() && packet.u8(0) == INPUT_HEADER) packet.copyOfRange(1, packet.size) else packet
}
