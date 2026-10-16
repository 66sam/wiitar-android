/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One entry of /proc/bus/input/devices.
 *
 * @property sysfs e.g. "/devices/virtual/misc/uhid/0005:057E:0306.0009/input/input31";
 *   devices created by the same Wiimote share the part before "/input/".
 */
data class KernelInputDevice(
    val name: String,
    val bus: Int,
    val vendor: Int,
    val product: Int,
    val sysfs: String,
    val handlers: List<String>,
) {
    /** "event13" etc., or null if the device has no evdev handler. */
    val eventHandler: String? get() = handlers.firstOrNull { it.startsWith("event") }

    /** Identifies the physical HID device: the sysfs path up to "/input/". */
    val hidParent: String get() = sysfs.substringBefore("/input/")

    companion object {
        /** Parses the text of /proc/bus/input/devices. */
        fun parseAll(text: String): List<KernelInputDevice> =
            text.split(Regex("\n\\s*\n")).mapNotNull { parseBlock(it) }

        private fun parseBlock(block: String): KernelInputDevice? {
            var name: String? = null
            var bus = 0
            var vendor = 0
            var product = 0
            var sysfs = ""
            var handlers = emptyList<String>()
            for (line in block.lines()) {
                val value = line.substringAfter(": ", "")
                when {
                    line.startsWith("I:") -> {
                        val fields = value.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
                        bus = fields["Bus"]?.toIntOrNull(16) ?: 0
                        vendor = fields["Vendor"]?.toIntOrNull(16) ?: 0
                        product = fields["Product"]?.toIntOrNull(16) ?: 0
                    }
                    line.startsWith("N:") -> name = value.removePrefix("Name=").trim('"')
                    line.startsWith("S:") -> sysfs = value.removePrefix("Sysfs=")
                    line.startsWith("H:") -> handlers = value.removePrefix("Handlers=").trim().split(' ').filter { it.isNotEmpty() }
                }
            }
            return name?.let { KernelInputDevice(it, bus, vendor, product, sysfs, handlers) }
        }
    }
}

/**
 * A Linux struct input_event.
 *
 * @property timeMicros the kernel timestamp (CLOCK_REALTIME unless the reader
 *   changed the clock), in microseconds; 0 when built by hand.
 */
data class EvdevEvent(val type: Int, val code: Int, val value: Int, val timeMicros: Long = 0) {
    companion object {
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_ABS = 0x03
        const val SYN_REPORT = 0

        /** sizeof(struct input_event) for 64-bit userspace (two 8-byte timeval fields). */
        const val SIZE_64 = 24

        /** sizeof(struct input_event) for 32-bit userspace. */
        const val SIZE_32 = 16

        /** Decodes one event from [buffer] at [offset]; [size] is [SIZE_64] or [SIZE_32]. */
        fun decode(buffer: ByteArray, offset: Int, size: Int = SIZE_64): EvdevEvent {
            val b = ByteBuffer.wrap(buffer, offset, size).order(ByteOrder.LITTLE_ENDIAN)
            val sec: Long
            val usec: Long
            if (size == SIZE_64) {
                sec = b.long
                usec = b.long
            } else {
                sec = b.int.toLong()
                usec = b.int.toLong()
            }
            val type = b.short.toInt() and 0xFFFF
            val code = b.short.toInt() and 0xFFFF
            return EvdevEvent(type, code, b.int, sec * 1_000_000 + usec)
        }

        fun encode(event: EvdevEvent, size: Int = SIZE_64): ByteArray {
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            val sec = event.timeMicros / 1_000_000
            val usec = event.timeMicros % 1_000_000
            if (size == SIZE_64) b.putLong(sec).putLong(usec) else b.putInt(sec.toInt()).putInt(usec.toInt())
            b.putShort(event.type.toShort()).putShort(event.code.toShort()).putInt(event.value)
            return b.array()
        }
    }
}

/** Linux input event codes used by the bridge (from include/uapi/linux/input-event-codes.h). */
object Codes {
    const val BTN_1 = 0x101
    const val BTN_2 = 0x102
    const val BTN_3 = 0x103
    const val BTN_4 = 0x104
    const val BTN_5 = 0x105
    const val BTN_SOUTH = 0x130
    const val BTN_EAST = 0x131
    const val BTN_C = 0x132
    const val BTN_NORTH = 0x133
    const val BTN_WEST = 0x134
    const val BTN_Z = 0x135
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b
    const val BTN_DPAD_UP = 0x220
    const val BTN_DPAD_DOWN = 0x221

    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_RX = 0x03
    const val ABS_RY = 0x04
    const val ABS_RZ = 0x05
    const val ABS_HAT0X = 0x10
    const val ABS_HAT0Y = 0x11
    const val ABS_HAT1X = 0x12
}

/**
 * Messages for /dev/uhid (include/uapi/linux/uhid.h). Writing these to the
 * device node creates a virtual HID device that the kernel, and so Android,
 * treats like real hardware. Only write() is needed, no ioctls, so this works
 * from plain Java file I/O.
 */
object Uhid {
    const val UHID_DESTROY = 1
    const val UHID_CREATE2 = 11
    const val UHID_INPUT2 = 12

    const val BUS_USB = 0x03
    const val BUS_VIRTUAL = 0x06

    /** sizeof(struct uhid_event): 4-byte type + the largest union member (uhid_create2_req). */
    const val EVENT_SIZE = 4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + 4096

    fun create2(
        name: String,
        descriptor: ByteArray,
        vendor: Int,
        product: Int,
        bus: Int = BUS_VIRTUAL,
        uniq: String = "",
    ): ByteArray {
        require(descriptor.size <= 4096) { "HID descriptor too large" }
        val b = event(UHID_CREATE2)
        putFixedString(b, name, 128)
        putFixedString(b, "wiitar", 64)
        putFixedString(b, uniq, 64)
        b.putShort(descriptor.size.toShort())
        b.putShort(bus.toShort())
        b.putInt(vendor)
        b.putInt(product)
        b.putInt(0) // version
        b.putInt(0) // country
        b.put(descriptor)
        return b.array()
    }

    fun input2(report: ByteArray): ByteArray {
        require(report.size <= 4096) { "report too large" }
        val b = event(UHID_INPUT2)
        b.putShort(report.size.toShort())
        b.put(report)
        return b.array()
    }

    fun destroy(): ByteArray = event(UHID_DESTROY).array()

    private fun event(type: Int): ByteBuffer =
        ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN).putInt(type)

    private fun putFixedString(b: ByteBuffer, s: String, size: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8).copyOf(size - 1) // keep a NUL terminator
        b.put(bytes).put(0)
    }
}
