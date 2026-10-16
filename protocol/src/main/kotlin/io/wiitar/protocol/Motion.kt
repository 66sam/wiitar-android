/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.protocol

import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Accelerometer calibration from Wiimote EEPROM 0x0016 (10 bytes):
 *  - 0-2: zero-g X/Y/Z (high 8 bits), 3: their LSB pairs (bits 5-4 X, 3-2 Y, 1-0 Z)
 *  - 4-6: one-g X/Y/Z (high 8 bits), 7: their LSB pairs
 *  - 9: checksum = (sum of bytes 0-8 + 0x55) and 0xFF
 */
data class AccelCalibration(
    val zero: AccelRaw,
    val oneG: AccelRaw,
    val checksumValid: Boolean,
) {
    companion object {
        const val EEPROM_ADDRESS = 0x0016
        const val SIZE = 10

        /** Used until (or if) the EEPROM block can't be read. Typical values, not measured. */
        val DEFAULT = AccelCalibration(AccelRaw(512, 512, 512), AccelRaw(616, 616, 616), checksumValid = false)

        fun parse(data: ByteArray): AccelCalibration {
            require(data.size >= SIZE) { "accelerometer calibration needs $SIZE bytes" }
            fun axes(at: Int) = data.u8(at + 3).let { lsb ->
                AccelRaw(
                    x = (data.u8(at) shl 2) or ((lsb shr 4) and 0x03),
                    y = (data.u8(at + 1) shl 2) or ((lsb shr 2) and 0x03),
                    z = (data.u8(at + 2) shl 2) or (lsb and 0x03),
                )
            }
            val sum = (0 until 9).sumOf { data.u8(it) }
            return AccelCalibration(axes(0), axes(4), data.u8(9) == (sum + 0x55) and 0xFF)
        }
    }

    /** Acceleration in g per axis. */
    fun toG(raw: AccelRaw): Triple<Float, Float, Float> {
        fun axis(v: Int, z: Int, one: Int) = if (one == z) 0f else (v - z).toFloat() / (one - z)
        return Triple(axis(raw.x, zero.x, oneG.x), axis(raw.y, zero.y, oneG.y), axis(raw.z, zero.z, oneG.z))
    }
}

enum class Axis { X, Y, Z }

/**
 * How guitar tilt is derived from the Wiimote's accelerometer. The Wiimote
 * sits differently in different guitar bodies, so the axis and direction are
 * configurable; the defaults are a starting point to be confirmed on hardware
 * via the diagnostics screen.
 */
data class TiltConfig(
    val axis: Axis = Axis.Y,
    val invert: Boolean = false,
    /** Angle (degrees from level along [axis]) that maps to tilt 0.0. */
    val restDegrees: Float = 0f,
    /** Angle that maps to tilt 1.0. */
    val fullDegrees: Float = 45f,
)

data class Tilt(
    /** Signed angle of [TiltConfig.axis] above level, in degrees. */
    val degrees: Float,
    /** 0.0 at rest .. 1.0 at full tilt. */
    val amount: Float,
)

object TiltCalculator {
    fun compute(raw: AccelRaw, calibration: AccelCalibration, config: TiltConfig): Tilt {
        val (x, y, z) = calibration.toG(raw)
        val magnitude = sqrt(x * x + y * y + z * z)
        val component = when (config.axis) {
            Axis.X -> x
            Axis.Y -> y
            Axis.Z -> z
        } * if (config.invert) -1f else 1f
        val degrees = if (magnitude < 1e-3f) 0f
        else Math.toDegrees(asin((component / magnitude).coerceIn(-1f, 1f).toDouble())).toFloat()
        val span = config.fullDegrees - config.restDegrees
        val amount = if (span == 0f) 0f else ((degrees - config.restDegrees) / span).coerceIn(0f, 1f)
        return Tilt(degrees, amount)
    }
}
