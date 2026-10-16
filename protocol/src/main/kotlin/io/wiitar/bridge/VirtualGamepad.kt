/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The virtual controller the bridge presents: a plain HID gamepad with 15
 * buttons, a hat switch and six axes. Through Linux hid-generic and Android's
 * Generic.kl, HID button N becomes BTN_GAMEPAD + N - 1, so buttons 1/2/4/5/7/8
 * arrive as Android's A/B/X/Y/L1/R1: the layout games expect from an Xbox-style
 * pad.
 */
object VirtualGamepad {
    enum class Button(val hidIndex: Int) {
        A(1),
        B(2),
        X(4),
        Y(5),
        L1(7),
        R1(8),
        L2(9),
        R2(10),
        SELECT(11),
        START(12),
        MODE(13),
        THUMB_L(14),
        THUMB_R(15),
    }

    /** Axes in report order. Through hid-generic they become ABS_X/Y/Z/RZ/RX/RY. */
    enum class Axis { X, Y, Z, RZ, RX, RY }

    enum class Hat { NONE, UP, DOWN, LEFT, RIGHT }

    /** Our own unregistered IDs, chosen so no specialised kernel driver binds. */
    const val VENDOR = 0x1209
    const val PRODUCT = 0x5754

    val DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01, //       Usage Page (Generic Desktop)
        0x09, 0x05, //       Usage (Gamepad)
        0xA1.b, 0x01, //     Collection (Application)
        0x05, 0x09, //         Usage Page (Button)
        0x19, 0x01, //         Usage Minimum (1)
        0x29, 0x0F, //         Usage Maximum (15)
        0x15, 0x00, //         Logical Minimum (0)
        0x25, 0x01, //         Logical Maximum (1)
        0x75, 0x01, //         Report Size (1)
        0x95.b, 0x0F, //       Report Count (15)
        0x81.b, 0x02, //       Input (Data, Variable, Absolute)
        0x75, 0x01, //         Report Size (1)
        0x95.b, 0x01, //       Report Count (1)
        0x81.b, 0x03, //       Input (Constant) — pad to 16 bits
        0x05, 0x01, //         Usage Page (Generic Desktop)
        0x09, 0x39, //         Usage (Hat switch)
        0x15, 0x00, //         Logical Minimum (0)
        0x25, 0x07, //         Logical Maximum (7)
        0x35, 0x00, //         Physical Minimum (0)
        0x46, 0x3B, 0x01, //   Physical Maximum (315)
        0x65, 0x14, //         Unit (Degrees)
        0x75, 0x04, //         Report Size (4)
        0x95.b, 0x01, //       Report Count (1)
        0x81.b, 0x42, //       Input (Data, Variable, Absolute, Null state)
        0x65, 0x00, //         Unit (None)
        0x75, 0x04, //         Report Size (4)
        0x95.b, 0x01, //       Report Count (1)
        0x81.b, 0x03, //       Input (Constant) — pad to a byte
        0x09, 0x30, //         Usage (X)
        0x09, 0x31, //         Usage (Y)
        0x09, 0x32, //         Usage (Z)
        0x09, 0x35, //         Usage (Rz)
        0x09, 0x33, //         Usage (Rx)
        0x09, 0x34, //         Usage (Ry)
        0x16, 0x01, 0x80.b, // Logical Minimum (-32767)
        0x26, 0xFF.b, 0x7F, // Logical Maximum (32767)
        0x75, 0x10, //         Report Size (16)
        0x95.b, 0x06, //       Report Count (6)
        0x81.b, 0x02, //       Input (Data, Variable, Absolute)
        0xC0.b, //           End Collection
    )

    const val REPORT_SIZE = 2 + 1 + 6 * 2

    /**
     * @param axes values in -1.0..1.0, indexed by [Axis.ordinal].
     */
    fun report(buttons: Set<Button>, hat: Hat, axes: FloatArray): ByteArray {
        require(axes.size == Axis.entries.size)
        val b = ByteBuffer.allocate(REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        var bits = 0
        for (button in buttons) bits = bits or (1 shl (button.hidIndex - 1))
        b.putShort(bits.toShort())
        b.put(
            when (hat) {
                Hat.UP -> 0
                Hat.RIGHT -> 2
                Hat.DOWN -> 4
                Hat.LEFT -> 6
                Hat.NONE -> 8 // outside the logical range = null (centred)
            }.toByte(),
        )
        for (v in axes) b.putShort((v.coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
        return b.array()
    }
}

private val Int.b: Byte get() = toByte()
