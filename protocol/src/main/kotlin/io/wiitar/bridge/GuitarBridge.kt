/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

import io.wiitar.protocol.AccelCalibration
import io.wiitar.protocol.AccelRaw
import io.wiitar.protocol.AutoRange
import io.wiitar.protocol.Fret
import io.wiitar.protocol.Tilt
import io.wiitar.protocol.TiltCalculator
import io.wiitar.protocol.TiltConfig

/** Which evdev device an event came from. */
enum class GuitarSource {
    /** The kernel hid-wiimote guitar device ("Nintendo Wii Remote Guitar", 057e:0306): has whammy. */
    KERNEL_GUITAR,

    /** The AYN Odin's re-published copy (same name, 2020:0111): Xbox-style codes, no whammy. */
    ODIN_GUITAR,

    /** "Nintendo Wii Remote Accelerometer": ABS_RX/RY/RZ, raw counts minus 0x200. */
    ACCELEROMETER,
}

/** Current guitar state, assembled from one or more evdev devices. */
data class GuitarInput(
    val frets: Set<Fret> = emptySet(),
    val strumUp: Boolean = false,
    val strumDown: Boolean = false,
    val plus: Boolean = false,
    val minus: Boolean = false,
    val stickX: Float = 0f,
    val stickY: Float = 0f,
    /** 0..1, or null if no source has reported a whammy value. */
    val whammy: Float? = null,
    /** -1..1 along the strip, or null when not touched / not present. */
    val touchStrip: Float? = null,
    val tilt: Tilt? = null,
)

/**
 * Where each guitar input goes on the virtual gamepad.
 *
 * No fret uses B: Android turns an unhandled BUTTON_B into BACK (Generic.kcm
 * fallback), which made the red fret leave Clone Hero's binding screen on the
 * Odin 3.
 */
data class BridgeMapping(
    val frets: Map<Fret, VirtualGamepad.Button> = mapOf(
        Fret.GREEN to VirtualGamepad.Button.A,
        Fret.RED to VirtualGamepad.Button.X,
        Fret.YELLOW to VirtualGamepad.Button.Y,
        Fret.BLUE to VirtualGamepad.Button.L1,
        Fret.ORANGE to VirtualGamepad.Button.R1,
    ),
    val plus: VirtualGamepad.Button = VirtualGamepad.Button.START,
    val minus: VirtualGamepad.Button = VirtualGamepad.Button.SELECT,
    val whammyAxis: VirtualGamepad.Axis = VirtualGamepad.Axis.Z,
    val tiltAxis: VirtualGamepad.Axis = VirtualGamepad.Axis.RZ,
    val touchStripAxis: VirtualGamepad.Axis = VirtualGamepad.Axis.RX,
    /** Button pressed while tilt exceeds [tiltButtonThreshold] (for games that want star power on a button). */
    val tiltButton: VirtualGamepad.Button? = VirtualGamepad.Button.THUMB_R,
    val tiltButtonThreshold: Float = 0.6f,
    /** The tilt button releases below this, so sensor noise near the threshold doesn't make it flicker. */
    val tiltButtonRelease: Float = 0.4f,
    /** Used when there is no [tiltCalibration]: a fixed accelerometer axis. */
    val tilt: TiltConfig = TiltConfig(),
    /** Measured rest/raised poses; preferred over [tilt] when set and usable. */
    val tiltCalibration: TiltCalibration? = null,
    /** Low-pass factor for the accelerometer (0..1, higher = less smoothing). */
    val tiltSmoothing: Float = 0.25f,
)

/**
 * Turns evdev events from the guitar's input devices into virtual-gamepad
 * reports. Pure logic, no I/O: feed [onEvent], send what it returns.
 */
class GuitarBridge(private val mapping: BridgeMapping = BridgeMapping()) {
    var state = GuitarInput()
        private set

    private val whammyRange = AutoRange(minimumSpan = 8)
    private val accel = IntArray(3)
    private var tiltButtonDown = false
    private var accelDirty = false

    /** Smoothed gravity direction from the accelerometer (raw counts), or null before the first sample. */
    var gravity: Vec3? = null
        private set
    private var lastReport: ByteArray? = null
    private var pending = false

    /**
     * @return a report to send when [event] completes a frame that changed the
     *   output (on SYN_REPORT), else null.
     */
    fun onEvent(source: GuitarSource, event: EvdevEvent): ByteArray? {
        when (event.type) {
            EvdevEvent.EV_KEY -> onKey(source, event.code, event.value != 0)
            EvdevEvent.EV_ABS -> onAbs(source, event.code, event.value)
            EvdevEvent.EV_SYN -> if (event.code == EvdevEvent.SYN_REPORT) {
                if (source == GuitarSource.ACCELEROMETER && accelDirty) {
                    accelDirty = false
                    state = state.copy(tilt = updateTilt())
                    pending = true
                }
                if (!pending) return null
                pending = false
                val report = report()
                if (!report.contentEquals(lastReport)) {
                    lastReport = report
                    return report
                }
            }
        }
        return null
    }

    /** The report for the current state, e.g. to send right after creating the device. */
    /** Virtual-gamepad buttons held in the current state. */
    fun pressedButtons(): Set<VirtualGamepad.Button> = buildSet {
        val s = state
        s.frets.forEach { mapping.frets[it]?.let(::add) }
        if (s.plus) add(mapping.plus)
        if (s.minus) add(mapping.minus)
        val tiltButton = mapping.tiltButton
        if (tiltButton != null && tiltButtonDown) add(tiltButton)
    }

    fun report(): ByteArray {
        val s = state
        val buttons = pressedButtons()
        val hat = when {
            s.strumUp && !s.strumDown -> VirtualGamepad.Hat.UP
            s.strumDown && !s.strumUp -> VirtualGamepad.Hat.DOWN
            else -> VirtualGamepad.Hat.NONE
        }
        val axes = FloatArray(VirtualGamepad.Axis.entries.size)
        axes[VirtualGamepad.Axis.X.ordinal] = s.stickX
        axes[VirtualGamepad.Axis.Y.ordinal] = s.stickY
        // Unipolar inputs span the whole axis so "released" reads as -1 and "full" as +1.
        s.whammy?.let { axes[mapping.whammyAxis.ordinal] = it * 2 - 1 }
        s.tilt?.let { axes[mapping.tiltAxis.ordinal] = it.amount * 2 - 1 }
        s.touchStrip?.let { axes[mapping.touchStripAxis.ordinal] = it }
        return VirtualGamepad.report(buttons, hat, axes)
    }

    private fun onKey(source: GuitarSource, code: Int, down: Boolean) {
        val fret = when (source) {
            GuitarSource.KERNEL_GUITAR -> KERNEL_FRETS[code]
            GuitarSource.ODIN_GUITAR -> ODIN_FRETS[code]
            GuitarSource.ACCELEROMETER -> null
        }
        state = when {
            fret != null -> state.copy(frets = if (down) state.frets + fret else state.frets - fret)
            code == Codes.BTN_START -> state.copy(plus = down)
            code == Codes.BTN_SELECT -> state.copy(minus = down)
            code == Codes.BTN_DPAD_UP -> state.copy(strumUp = down)
            code == Codes.BTN_DPAD_DOWN -> state.copy(strumDown = down)
            else -> return
        }
        pending = true
    }

    private fun onAbs(source: GuitarSource, code: Int, value: Int) {
        state = when (source) {
            GuitarSource.ACCELEROMETER -> {
                val axis = code - Codes.ABS_RX
                if (axis !in 0..2) return
                accel[axis] = value
                // Tilt is computed once the frame is complete (SYN_REPORT), not per axis.
                accelDirty = true
                return
            }
            GuitarSource.KERNEL_GUITAR -> when (code) {
                // hid-wiimote: stick is (raw - 0x20), so about -32..31.
                Codes.ABS_X -> state.copy(stickX = (value / 31f).coerceIn(-1f, 1f))
                Codes.ABS_Y -> state.copy(stickY = (value / 31f).coerceIn(-1f, 1f))
                Codes.ABS_HAT0X -> state.copy(touchStrip = kernelTouchStrip(value))
                Codes.ABS_HAT1X -> state.copy(whammy = whammyRange.normalize(value))
                else -> return
            }
            GuitarSource.ODIN_GUITAR -> when (code) {
                Codes.ABS_X -> state.copy(stickX = value / 32767f)
                Codes.ABS_Y -> state.copy(stickY = value / 32767f)
                Codes.ABS_HAT0X -> state.copy(touchStrip = value.toFloat().takeIf { value != 0 })
                Codes.ABS_HAT0Y -> state.copy(strumUp = value < 0, strumDown = value > 0)
                else -> return
            }
        }
        pending = true
    }

    private fun updateTilt(): Tilt {
        // The kernel reports raw 10-bit counts minus 0x200, i.e. already centred on zero g.
        val sample = Vec3(accel[0].toFloat(), accel[1].toFloat(), accel[2].toFloat())
        val g = gravity?.let { it + (sample - it) * mapping.tiltSmoothing } ?: sample
        gravity = g
        val calibration = mapping.tiltCalibration?.takeIf { it.usable }
        val tilt = if (calibration != null) {
            Tilt(calibration.degreesFromRest(g), calibration.amount(g))
        } else {
            val raw = AccelRaw(g.x.toInt() + 0x200, g.y.toInt() + 0x200, g.z.toInt() + 0x200)
            TiltCalculator.compute(raw, AccelCalibration.DEFAULT, mapping.tilt)
        }
        tiltButtonDown = if (tiltButtonDown) {
            tilt.amount > mapping.tiltButtonRelease
        } else {
            tilt.amount >= mapping.tiltButtonThreshold
        }
        return tilt
    }

    /** hid-wiimote reports the raw 5-bit strip value; 0x0F means "not touched". */
    private fun kernelTouchStrip(value: Int): Float? =
        if (value == 0x0F) null else ((value - 0x04).coerceIn(0, 0x1B) / 0x1B.toFloat()) * 2 - 1

    companion object {
        /** hid-wiimote guitar fret keys. */
        val KERNEL_FRETS = mapOf(
            Codes.BTN_1 to Fret.GREEN,
            Codes.BTN_2 to Fret.RED,
            Codes.BTN_3 to Fret.YELLOW,
            Codes.BTN_4 to Fret.BLUE,
            Codes.BTN_5 to Fret.ORANGE,
        )

        /** The Odin's re-published guitar, as measured with the guided test. */
        val ODIN_FRETS = mapOf(
            Codes.BTN_EAST to Fret.GREEN,
            Codes.BTN_C to Fret.RED,
            Codes.BTN_NORTH to Fret.YELLOW,
            Codes.BTN_WEST to Fret.BLUE,
            Codes.BTN_Z to Fret.ORANGE,
        )
    }
}
