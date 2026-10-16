/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

import kotlin.math.acos
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length: Float get() = sqrt(this dot this)
    fun normalized(): Vec3 = length.let { if (it < 1e-6f) this else this * (1f / it) }

    /** "x,y,z", for saving in preferences. */
    fun encode() = "$x,$y,$z"

    companion object {
        fun decode(s: String?): Vec3? = s?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }
    }
}

/**
 * Tilt measured along the user's own motion, so it works however the Wii
 * Remote is mounted in the guitar: [rest] is the gravity direction with the
 * guitar held normally, [up] with the neck raised. Tilt is the position of
 * the current gravity direction along the rest → up path, 0..1.
 */
data class TiltCalibration(val rest: Vec3, val up: Vec3) {
    private val path = up.normalized() - rest.normalized()
    private val pathLengthSquared = path dot path

    /** False if the two poses were (nearly) the same, so no tilt can be measured. */
    val usable: Boolean get() = pathLengthSquared > 0.02f

    fun amount(gravity: Vec3): Float {
        if (!usable) return 0f
        return (((gravity.normalized() - rest.normalized()) dot path) / pathLengthSquared).coerceIn(0f, 1f)
    }

    /** Angle between [gravity] and the rest pose, in degrees. */
    fun degreesFromRest(gravity: Vec3): Float =
        Math.toDegrees(acos((gravity.normalized() dot rest.normalized()).coerceIn(-1f, 1f).toDouble())).toFloat()

    fun encode() = "${rest.encode()};${up.encode()}"

    companion object {
        fun decode(s: String?): TiltCalibration? {
            val parts = s?.split(';') ?: return null
            if (parts.size != 2) return null
            val rest = Vec3.decode(parts[0]) ?: return null
            val up = Vec3.decode(parts[1]) ?: return null
            return TiltCalibration(rest, up)
        }
    }
}
