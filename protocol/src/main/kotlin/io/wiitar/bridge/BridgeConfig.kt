/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

import io.wiitar.protocol.Axis
import io.wiitar.protocol.TiltConfig

/**
 * Everything the user can configure about the bridge, in one value that is
 * saved by the app and sent to the bridge service as text.
 */
data class BridgeConfig(
    /** Encoded [ButtonLayout] (which gamepad button each control presses); empty = defaults. */
    val buttons: String = "",
    /** Which physical input is which control. */
    val inputs: InputLayout = InputLayout.DEFAULT,
    val touchStripMode: TouchStripMode = TouchStripMode.AXIS,
    val tiltCalibration: TiltCalibration? = null,
    /** Fixed accelerometer axis, used only without [tiltCalibration]. */
    val tiltAxis: Axis = Axis.Y,
    val tiltInvert: Boolean = false,
    /** Take exclusive access to the guitar's own input device so games only see the virtual gamepad. */
    val hideGuitar: Boolean = true,
) {
    fun mapping(): BridgeMapping = ButtonLayout.decode(
        buttons,
        BridgeMapping(
            tilt = TiltConfig(axis = tiltAxis, invert = tiltInvert),
            tiltCalibration = tiltCalibration,
            touchStripMode = touchStripMode,
        ),
    )

    /** One "key<TAB>value" line per setting. */
    fun encode(): String = listOf(
        "buttons" to buttons,
        "inputs" to inputs.encode(),
        "touchStrip" to touchStripMode.name,
        "tiltCalibration" to (tiltCalibration?.encode() ?: ""),
        "tiltAxis" to tiltAxis.name,
        "tiltInvert" to tiltInvert.toString(),
        "hideGuitar" to hideGuitar.toString(),
    ).joinToString("\n") { (k, v) -> "$k\t$v" }

    companion object {
        /** Missing or malformed settings fall back to the defaults. */
        fun decode(text: String?): BridgeConfig {
            val values = text.orEmpty().lines()
                .filter { '\t' in it }
                .associate { it.substringBefore('\t') to it.substringAfter('\t') }
            val d = BridgeConfig()
            return BridgeConfig(
                buttons = values["buttons"] ?: d.buttons,
                inputs = values["inputs"]?.let { InputLayout.decode(it) } ?: d.inputs,
                touchStripMode = TouchStripMode.entries.firstOrNull { it.name == values["touchStrip"] } ?: d.touchStripMode,
                tiltCalibration = TiltCalibration.decode(values["tiltCalibration"]),
                tiltAxis = Axis.entries.firstOrNull { it.name == values["tiltAxis"] } ?: d.tiltAxis,
                tiltInvert = values["tiltInvert"]?.toBooleanStrictOrNull() ?: d.tiltInvert,
                hideGuitar = values["hideGuitar"]?.toBooleanStrictOrNull() ?: d.hideGuitar,
            )
        }
    }
}

/**
 * Per-guitar settings: guitar id (the Wii Remote's Bluetooth address) → its
 * own [BridgeConfig]. Guitars without an entry use the shared default.
 */
object Profiles {
    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'

    fun encode(profiles: Map<String, BridgeConfig>): String =
        profiles.entries.joinToString(RECORD.toString()) { (id, config) -> "$id$FIELD${config.encode()}" }

    fun decode(text: String?): Map<String, BridgeConfig> =
        text.orEmpty().split(RECORD).filter { FIELD in it }.associate {
            it.substringBefore(FIELD) to BridgeConfig.decode(it.substringAfter(FIELD))
        }
}

/**
 * One guitar's live state as a flat int array, so the app can poll it over
 * binder cheaply for the on-screen guitar. Floats are scaled by 1000.
 */
object LiveState {
    const val SIZE = 12

    const val SLOT = 0
    const val FRETS = 1 // bit per Fret.ordinal (buttons and, in FRETS mode, the touch strip)
    const val STRUM = 2 // -1 up, 1 down, 0 none
    const val PLUS = 3
    const val MINUS = 4
    const val TILT = 5 // 0..1000, or -1 without an accelerometer
    const val WHAMMY = 6 // 0..1000, or -1 when not available
    const val TOUCH = 7 // -1000..1000, or NO_TOUCH
    const val STAR_POWER = 8 // 1 while the tilt button is held
    const val KERNEL_SOURCE = 9 // 1 = kernel guitar device, 0 = the Odin's copy
    const val TOUCH_PRESENT = 10 // 1 once a touch strip has been seen
    const val BATTERY = 11 // percent, or -1 if unknown

    const val NO_TOUCH = -9999

    fun of(slot: Int, bridge: GuitarBridge, kernelSource: Boolean, touchSeen: Boolean, battery: Int = -1): IntArray {
        val s = bridge.state
        val fretBits = (s.frets + if (bridge.touchFretsActive) s.touchFrets else emptySet())
            .fold(0) { bits, fret -> bits or (1 shl fret.ordinal) }
        return intArrayOf(
            slot,
            fretBits,
            if (s.strumUp) -1 else if (s.strumDown) 1 else 0,
            if (s.plus) 1 else 0,
            if (s.minus) 1 else 0,
            s.tilt?.let { (it.amount * 1000).toInt() } ?: -1,
            bridge.effectiveWhammy()?.let { (it * 1000).toInt() } ?: -1,
            s.touchStrip?.let { (it * 1000).toInt() } ?: NO_TOUCH,
            if (bridge.tiltButtonHeld) 1 else 0,
            if (kernelSource) 1 else 0,
            if (touchSeen) 1 else 0,
            battery,
        )
    }
}
