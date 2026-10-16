/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.protocol

/**
 * Data reporting modes (report IDs 0x30-0x3D). Offsets are into the payload,
 * i.e. the bytes after the report ID.
 *
 * Note: the guitar tilt sensor is the Wiimote's own accelerometer, so the
 * mode we want with a guitar attached is 0x35 (core buttons + accel + 16
 * extension bytes). 0x34 carries 19 extension bytes but no accelerometer.
 *
 * Reference: https://wiibrew.org/wiki/Wiimote#Data_Reporting
 */
enum class ReportMode(
    val id: Int,
    val hasButtons: Boolean,
    val accelOffset: Int?,
    val extOffset: Int?,
    val extLength: Int,
) {
    CORE(0x30, true, null, null, 0),
    CORE_ACCEL(0x31, true, 2, null, 0),
    CORE_EXT8(0x32, true, null, 2, 8),
    CORE_ACCEL_IR12(0x33, true, 2, null, 0),
    CORE_EXT19(0x34, true, null, 2, 19),
    CORE_ACCEL_EXT16(0x35, true, 2, 5, 16),
    CORE_IR10_EXT9(0x36, true, null, 12, 9),
    CORE_ACCEL_IR10_EXT6(0x37, true, 2, 15, 6),
    EXT21(0x3D, false, null, 0, 21);

    companion object {
        fun fromId(id: Int): ReportMode? = entries.firstOrNull { it.id == id }
    }
}

/** Core Wiimote buttons, packed as (firstByte shl 8) or secondByte with accelerometer bits removed. */
@JvmInline
value class CoreButtons(val bits: Int) {
    operator fun contains(button: CoreButton): Boolean = bits and button.mask != 0

    val pressed: List<CoreButton> get() = CoreButton.entries.filter { it in this }

    override fun toString(): String = "CoreButtons(${pressed.joinToString()})"

    companion object {
        /** Buttons share their bytes with accelerometer LSBs; these are the real button bits. */
        private const val MASK = 0x1F9F

        fun parse(payload: ByteArray, offset: Int = 0): CoreButtons =
            CoreButtons(((payload.u8(offset) shl 8) or payload.u8(offset + 1)) and MASK)
    }
}

enum class CoreButton(val mask: Int) {
    DPAD_LEFT(0x0100),
    DPAD_RIGHT(0x0200),
    DPAD_DOWN(0x0400),
    DPAD_UP(0x0800),
    PLUS(0x1000),
    TWO(0x0001),
    ONE(0x0002),
    B(0x0004),
    A(0x0008),
    MINUS(0x0010),
    HOME(0x0080),
}

/** Raw 10-bit accelerometer sample (0..1023 per axis). */
data class AccelRaw(val x: Int, val y: Int, val z: Int) {
    companion object {
        /**
         * The 8 high bits of each axis are in the accel bytes; X gets its two
         * LSBs from bits 5-6 of button byte 0, while Y and Z only get bit 1 from
         * bit 5 / bit 6 of button byte 1 (their bit 0 is always zero).
         */
        fun parse(payload: ByteArray, buttonsOffset: Int, accelOffset: Int): AccelRaw {
            val b0 = payload.u8(buttonsOffset)
            val b1 = payload.u8(buttonsOffset + 1)
            return AccelRaw(
                x = (payload.u8(accelOffset) shl 2) or ((b0 shr 5) and 0x03),
                y = (payload.u8(accelOffset + 1) shl 2) or ((b1 shr 4) and 0x02),
                z = (payload.u8(accelOffset + 2) shl 2) or ((b1 shr 5) and 0x02),
            )
        }
    }
}

sealed interface InputReport {
    val reportId: Int

    /** 0x20: battery, extension-present flag, LEDs. Sent on request and on extension plug/unplug. */
    data class Status(
        val buttons: CoreButtons,
        val batteryLow: Boolean,
        val extensionConnected: Boolean,
        val speakerEnabled: Boolean,
        val irEnabled: Boolean,
        val ledMask: Int,
        val battery: Int,
    ) : InputReport {
        override val reportId get() = 0x20
    }

    /** 0x21: up to 16 bytes of memory read data. [addressLow] is the low 16 bits of the chunk's address. */
    class ReadData(
        val buttons: CoreButtons,
        val error: Int,
        val addressLow: Int,
        val data: ByteArray,
    ) : InputReport {
        override val reportId get() = 0x21
        override fun toString() = "ReadData(error=$error, addressLow=0x%04x, data=${data.toHex()})".format(addressLow)
    }

    /** 0x22: acknowledgement of output report [ackedReportId]; [error] 0 = success. */
    data class Ack(val buttons: CoreButtons, val ackedReportId: Int, val error: Int) : InputReport {
        override val reportId get() = 0x22
    }

    /** 0x30-0x3D: continuous input. */
    class Data(
        val mode: ReportMode,
        val buttons: CoreButtons?,
        val accel: AccelRaw?,
        val extension: ByteArray?,
    ) : InputReport {
        override val reportId get() = mode.id
        override fun toString() = "Data(${mode.name}, $buttons, $accel, ext=${extension?.toHex()})"
    }

    class Unknown(override val reportId: Int, val payload: ByteArray) : InputReport {
        override fun toString() = "Unknown(0x%02x, ${payload.toHex()})".format(reportId)
    }

    companion object {
        /**
         * Parses one input report. Accepts the report with or without the 0xA1
         * HID header. Returns null for an empty or truncated packet.
         */
        fun parse(packet: ByteArray): InputReport? {
            val report = HidFraming.unwrapInput(packet)
            if (report.isEmpty()) return null
            val id = report.u8(0)
            val p = report.copyOfRange(1, report.size)
            return when (id) {
                0x20 -> if (p.size < 6) null else {
                    val flags = p.u8(2)
                    Status(
                        buttons = CoreButtons.parse(p),
                        batteryLow = flags and 0x01 != 0,
                        extensionConnected = flags and 0x02 != 0,
                        speakerEnabled = flags and 0x04 != 0,
                        irEnabled = flags and 0x08 != 0,
                        ledMask = flags shr 4,
                        battery = p.u8(5),
                    )
                }
                0x21 -> if (p.size < 21) null else {
                    val se = p.u8(2)
                    val size = (se shr 4) + 1
                    ReadData(
                        buttons = CoreButtons.parse(p),
                        error = se and 0x0F,
                        addressLow = (p.u8(3) shl 8) or p.u8(4),
                        data = p.copyOfRange(5, 5 + size),
                    )
                }
                0x22 -> if (p.size < 4) null else Ack(CoreButtons.parse(p), p.u8(2), p.u8(3))
                else -> {
                    val mode = ReportMode.fromId(id) ?: return Unknown(id, p)
                    val needed = maxOf(
                        if (mode.hasButtons) 2 else 0,
                        mode.accelOffset?.plus(3) ?: 0,
                        mode.extOffset?.plus(mode.extLength) ?: 0,
                    )
                    if (p.size < needed) return null
                    Data(
                        mode = mode,
                        buttons = if (mode.hasButtons) CoreButtons.parse(p) else null,
                        accel = mode.accelOffset?.let { AccelRaw.parse(p, 0, it) },
                        extension = mode.extOffset?.let { p.copyOfRange(it, it + mode.extLength) },
                    )
                }
            }
        }
    }
}
