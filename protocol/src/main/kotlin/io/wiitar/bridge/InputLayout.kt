/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

import io.wiitar.protocol.Fret

/** The digital guitar controls a user can (re)assign by pressing them. */
enum class GuitarControl(val label: String, val fret: Fret? = null) {
    GREEN("Green", Fret.GREEN),
    RED("Red", Fret.RED),
    YELLOW("Yellow", Fret.YELLOW),
    BLUE("Blue", Fret.BLUE),
    ORANGE("Orange", Fret.ORANGE),
    STRUM_UP("Strum up"),
    STRUM_DOWN("Strum down"),
    PLUS("Plus (+)"),
    MINUS("Minus (−) / Star Power"),
}

/**
 * One physical input on an evdev device: a key, or one direction of an
 * absolute axis that acts like a button (e.g. a hat reporting -1/0/+1).
 */
data class PhysicalInput(val type: Int, val code: Int, val direction: Int = 0) {
    /** Whether [value] of this input's event means "pressed". */
    fun pressed(value: Int): Boolean = if (type == EvdevEvent.EV_KEY) value != 0 else value * direction > 0

    fun encode() = "$type:$code:$direction"

    override fun toString() = when (type) {
        EvdevEvent.EV_KEY -> "key %d (0x%03x)".format(code, code)
        else -> "axis 0x%02x %s".format(code, if (direction < 0) "−" else "+")
    }

    companion object {
        fun key(code: Int) = PhysicalInput(EvdevEvent.EV_KEY, code)
        fun axis(code: Int, direction: Int) = PhysicalInput(EvdevEvent.EV_ABS, code, direction)

        fun decode(s: String): PhysicalInput? {
            val p = s.split(':').map { it.toIntOrNull() ?: return null }
            return if (p.size == 3) PhysicalInput(p[0], p[1], p[2]) else null
        }

        /**
         * The input a "press the control now" capture should record for
         * [event], or null if the event isn't a press. Keys count on press;
         * on the Odin's copy the hat axes (strum, touch strip) count when
         * they leave the centre. Analog axes (stick, whammy, accelerometer)
         * never count.
         */
        fun fromPress(source: GuitarSource, event: EvdevEvent): PhysicalInput? = when {
            source == GuitarSource.ACCELEROMETER -> null
            event.type == EvdevEvent.EV_KEY && event.value == 1 -> key(event.code)
            source == GuitarSource.ODIN_GUITAR && event.type == EvdevEvent.EV_ABS &&
                event.code in setOf(Codes.ABS_HAT0X, Codes.ABS_HAT0Y) && event.value != 0 ->
                axis(event.code, if (event.value < 0) -1 else 1)
            else -> null
        }
    }
}

/**
 * Which physical input drives each [GuitarControl], per guitar source (the
 * kernel device and the Odin's copy use different codes).
 */
data class InputLayout(val bindings: Map<GuitarSource, Map<GuitarControl, PhysicalInput>>) {
    fun forSource(source: GuitarSource): Map<GuitarControl, PhysicalInput> = bindings[source].orEmpty()

    /** Assigns [input] to [control]; any other control using the same input loses it. */
    fun with(source: GuitarSource, control: GuitarControl, input: PhysicalInput): InputLayout {
        val updated = forSource(source).filterValues { it != input } + (control to input)
        return InputLayout(bindings + (source to updated))
    }

    /** "KERNEL_GUITAR.GREEN=1:257:0,…" */
    fun encode(): String = bindings.flatMap { (source, map) ->
        map.map { (control, input) -> "${source.name}.${control.name}=${input.encode()}" }
    }.joinToString(",")

    companion object {
        val DEFAULT = InputLayout(
            mapOf(
                GuitarSource.KERNEL_GUITAR to mapOf(
                    GuitarControl.GREEN to PhysicalInput.key(Codes.BTN_1),
                    GuitarControl.RED to PhysicalInput.key(Codes.BTN_2),
                    GuitarControl.YELLOW to PhysicalInput.key(Codes.BTN_3),
                    GuitarControl.BLUE to PhysicalInput.key(Codes.BTN_4),
                    GuitarControl.ORANGE to PhysicalInput.key(Codes.BTN_5),
                    GuitarControl.STRUM_UP to PhysicalInput.key(Codes.BTN_DPAD_UP),
                    GuitarControl.STRUM_DOWN to PhysicalInput.key(Codes.BTN_DPAD_DOWN),
                    GuitarControl.PLUS to PhysicalInput.key(Codes.BTN_START),
                    GuitarControl.MINUS to PhysicalInput.key(Codes.BTN_SELECT),
                ),
                // The Odin's copy, as measured with the guided test.
                GuitarSource.ODIN_GUITAR to mapOf(
                    GuitarControl.GREEN to PhysicalInput.key(Codes.BTN_EAST),
                    GuitarControl.RED to PhysicalInput.key(Codes.BTN_C),
                    GuitarControl.YELLOW to PhysicalInput.key(Codes.BTN_NORTH),
                    GuitarControl.BLUE to PhysicalInput.key(Codes.BTN_WEST),
                    GuitarControl.ORANGE to PhysicalInput.key(Codes.BTN_Z),
                    GuitarControl.STRUM_UP to PhysicalInput.axis(Codes.ABS_HAT0Y, -1),
                    GuitarControl.STRUM_DOWN to PhysicalInput.axis(Codes.ABS_HAT0Y, 1),
                    GuitarControl.PLUS to PhysicalInput.key(Codes.BTN_START),
                    GuitarControl.MINUS to PhysicalInput.key(Codes.BTN_SELECT),
                ),
            ),
        )

        /** Applies [encoded] on top of [DEFAULT]; malformed entries are ignored. */
        fun decode(encoded: String?): InputLayout {
            var layout = DEFAULT
            for (entry in encoded.orEmpty().split(',')) {
                val key = entry.substringBefore('=', "")
                val source = GuitarSource.entries.firstOrNull { it.name == key.substringBefore('.') } ?: continue
                val control = GuitarControl.entries.firstOrNull { it.name == key.substringAfter('.') } ?: continue
                val input = PhysicalInput.decode(entry.substringAfter('=')) ?: continue
                layout = layout.with(source, control, input)
            }
            return layout
        }
    }
}
