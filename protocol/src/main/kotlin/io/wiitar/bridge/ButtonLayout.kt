/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

import io.wiitar.protocol.Fret

/**
 * The user-remappable buttons of a [BridgeMapping], stored as text such as
 * "GREEN=A,RED=X,YELLOW=Y,BLUE=L1,ORANGE=R1,PLUS=START,MINUS=SELECT,TILT=THUMB_R".
 */
object ButtonLayout {
    enum class Input(val label: String) {
        GREEN("Green fret"),
        RED("Red fret"),
        YELLOW("Yellow fret"),
        BLUE("Blue fret"),
        ORANGE("Orange fret"),
        PLUS("Plus (+)"),
        MINUS("Minus (−) / Star Power"),
        TILT("Tilt past threshold"),
    }

    /** Value for [Input.TILT] meaning "no button, axis only". */
    const val NONE = "NONE"

    fun get(mapping: BridgeMapping, input: Input): VirtualGamepad.Button? = when (input) {
        Input.GREEN -> mapping.frets[Fret.GREEN]
        Input.RED -> mapping.frets[Fret.RED]
        Input.YELLOW -> mapping.frets[Fret.YELLOW]
        Input.BLUE -> mapping.frets[Fret.BLUE]
        Input.ORANGE -> mapping.frets[Fret.ORANGE]
        Input.PLUS -> mapping.plus
        Input.MINUS -> mapping.minus
        Input.TILT -> mapping.tiltButton
    }

    fun set(mapping: BridgeMapping, input: Input, button: VirtualGamepad.Button?): BridgeMapping {
        fun fret(f: Fret) = mapping.copy(frets = if (button == null) mapping.frets - f else mapping.frets + (f to button))
        return when (input) {
            Input.GREEN -> fret(Fret.GREEN)
            Input.RED -> fret(Fret.RED)
            Input.YELLOW -> fret(Fret.YELLOW)
            Input.BLUE -> fret(Fret.BLUE)
            Input.ORANGE -> fret(Fret.ORANGE)
            Input.PLUS -> button?.let { mapping.copy(plus = it) } ?: mapping
            Input.MINUS -> button?.let { mapping.copy(minus = it) } ?: mapping
            Input.TILT -> mapping.copy(tiltButton = button)
        }
    }

    fun encode(mapping: BridgeMapping): String =
        Input.entries.joinToString(",") { "${it.name}=${get(mapping, it)?.name ?: NONE}" }

    /** Applies [encoded] on top of [base]; unknown or malformed entries are ignored. */
    fun decode(encoded: String?, base: BridgeMapping = BridgeMapping()): BridgeMapping {
        var mapping = base
        for (entry in encoded.orEmpty().split(',')) {
            val input = Input.entries.firstOrNull { it.name == entry.substringBefore('=').trim() } ?: continue
            val value = entry.substringAfter('=', "").trim()
            val button = VirtualGamepad.Button.entries.firstOrNull { it.name == value }
            if (button != null || (value == NONE && input == Input.TILT)) mapping = set(mapping, input, button)
        }
        return mapping
    }

    /** Buttons used by more than one input, for a warning in the UI. */
    fun duplicates(mapping: BridgeMapping): Set<VirtualGamepad.Button> =
        Input.entries.mapNotNull { get(mapping, it) }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
