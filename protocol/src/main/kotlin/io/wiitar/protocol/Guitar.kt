/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.protocol

enum class Fret { GREEN, RED, YELLOW, BLUE, ORANGE }

/**
 * Undecoded guitar fields from the first 6 extension bytes, kept for diagnostics.
 *
 * Byte layout (unencrypted), per WiiBrew "Guitar Hero (Wii) Guitars":
 *  - 0: bits 5-0 stick X (6 bit)
 *  - 1: bits 5-0 stick Y (6 bit)
 *  - 2: bits 4-0 touch strip (World Tour-style guitars only)
 *  - 3: bits 4-0 whammy bar
 *  - 4: bit 6 strum down, bit 4 minus, bit 2 plus           (active low)
 *  - 5: bit 7 orange, 6 red, 5 blue, 4 green, 3 yellow, 0 strum up (active low)
 */
data class GuitarRaw(
    val stickX: Int,
    val stickY: Int,
    val touchStrip: Int,
    val whammy: Int,
    val buttonBits: Int,
) {
    private fun pressed(byte: Int, bit: Int) = (buttonBits shr (if (byte == 4) 8 else 0)) and (1 shl bit) == 0

    val strumDown get() = pressed(4, 6)
    val minus get() = pressed(4, 4)
    val plus get() = pressed(4, 2)
    val strumUp get() = pressed(5, 0)

    val frets: Set<Fret>
        get() = buildSet {
            if (pressed(5, 4)) add(Fret.GREEN)
            if (pressed(5, 6)) add(Fret.RED)
            if (pressed(5, 3)) add(Fret.YELLOW)
            if (pressed(5, 5)) add(Fret.BLUE)
            if (pressed(5, 7)) add(Fret.ORANGE)
        }

    companion object {
        fun parse(ext: ByteArray): GuitarRaw {
            require(ext.size >= 6) { "guitar data needs 6 extension bytes, got ${ext.size}" }
            return GuitarRaw(
                stickX = ext.u8(0) and 0x3F,
                stickY = ext.u8(1) and 0x3F,
                touchStrip = ext.u8(2) and 0x1F,
                whammy = ext.u8(3) and 0x1F,
                buttonBits = (ext.u8(4) shl 8) or ext.u8(5),
            )
        }
    }
}

/**
 * A reading from a World Tour-style touch strip.
 *
 * @property position 0.0 (green end) .. 1.0 (orange end), or null when not touched.
 */
data class TouchStrip(val raw: Int, val frets: Set<Fret>, val position: Float?) {
    companion object {
        const val NOT_TOUCHED = 0x0F

        /**
         * WiiBrew lists these values: 0x04 G, 0x07 G+R, 0x0A R, 0x0C-0x0D R+Y,
         * 0x12-0x13 Y, 0x14-0x15 Y+B, 0x17-0x18 B, 0x1A B+O, 0x1F O, and 0x0F
         * for not touched. Values in between are put in the nearest band.
         * Needs checking against real hardware.
         */
        fun decode(raw: Int): TouchStrip {
            if (raw == NOT_TOUCHED) return TouchStrip(raw, emptySet(), null)
            val frets = when (raw) {
                in 0x00..0x05 -> setOf(Fret.GREEN)
                in 0x06..0x08 -> setOf(Fret.GREEN, Fret.RED)
                in 0x09..0x0B -> setOf(Fret.RED)
                in 0x0C..0x0E -> setOf(Fret.RED, Fret.YELLOW)
                in 0x10..0x13 -> setOf(Fret.YELLOW)
                in 0x14..0x16 -> setOf(Fret.YELLOW, Fret.BLUE)
                in 0x17..0x19 -> setOf(Fret.BLUE)
                in 0x1A..0x1C -> setOf(Fret.BLUE, Fret.ORANGE)
                else -> setOf(Fret.ORANGE)
            }
            val position = ((raw - 0x04).coerceIn(0, 0x1B) / 0x1B.toFloat())
            return TouchStrip(raw, frets, position)
        }
    }
}

/** Fully decoded guitar state, ready for mapping onto a virtual gamepad. */
data class GuitarState(
    val frets: Set<Fret>,
    val strumUp: Boolean,
    val strumDown: Boolean,
    val plus: Boolean,
    val minus: Boolean,
    /** -1.0 (left) .. 1.0 (right) */
    val stickX: Float,
    /** -1.0 (down) .. 1.0 (up) */
    val stickY: Float,
    /** 0.0 at rest .. 1.0 fully pressed */
    val whammy: Float,
    /** null unless this guitar has been seen to have a touch strip. */
    val touchStrip: TouchStrip?,
    val raw: GuitarRaw,
)

/**
 * Tracks the observed range of an analog input that has no documented
 * calibration. The first sample is taken as the rest position, and the range
 * grows as the user moves the control.
 */
class AutoRange(private val minimumSpan: Int) {
    var min = -1
        private set
    var max = -1
        private set

    fun normalize(value: Int): Float {
        if (min < 0) {
            min = value
            max = value + minimumSpan
        }
        if (value < min) min = value
        if (value > max) max = value
        return ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }
}

/**
 * Stateful per-connection guitar decoder. Create a new one whenever the
 * extension is (re)plugged so the analog ranges start fresh.
 */
class GuitarDecoder {
    private val whammyRange = AutoRange(minimumSpan = 10)
    private var touchStripIdleSeen = false
    private var touchStripActiveSeen = false

    /**
     * World Tour-style touch strips idle at 0x0F and change when touched.
     * A guitar without a strip reports a constant value, so we only call the
     * strip present after seeing the idle value *and* another value.
     */
    val hasTouchStrip: Boolean get() = touchStripIdleSeen && touchStripActiveSeen

    val whammyRestRaw: Int get() = whammyRange.min
    val whammyMaxRaw: Int get() = whammyRange.max

    fun decode(ext: ByteArray): GuitarState {
        val raw = GuitarRaw.parse(ext)
        if (raw.touchStrip == TouchStrip.NOT_TOUCHED) touchStripIdleSeen = true else touchStripActiveSeen = true
        return GuitarState(
            frets = raw.frets,
            strumUp = raw.strumUp,
            strumDown = raw.strumDown,
            plus = raw.plus,
            minus = raw.minus,
            stickX = stickAxis(raw.stickX),
            stickY = stickAxis(raw.stickY),
            whammy = whammyRange.normalize(raw.whammy),
            touchStrip = if (hasTouchStrip) TouchStrip.decode(raw.touchStrip) else null,
            raw = raw,
        )
    }

    /** 6-bit stick, centre 0x20. */
    private fun stickAxis(v: Int) = ((v - 0x20) / 31f).coerceIn(-1f, 1f)
}
