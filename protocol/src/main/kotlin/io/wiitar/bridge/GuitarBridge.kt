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
import io.wiitar.protocol.TouchStrip

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
    /** Frets under the finger on the touch strip (kernel source only; the Odin's copy can't tell). */
    val touchFrets: Set<Fret> = emptySet(),
    val tilt: Tilt? = null,
)

/** What the World Tour-style touch strip does on the virtual gamepad. */
enum class TouchStripMode(val label: String) {
    /** Position on [BridgeMapping.touchStripAxis]. */
    AXIS("Separate axis"),

    /** Touching a section presses that fret (tap notes), together with the fret buttons. */
    FRETS("Press frets"),

    /** Acts as the whammy bar when the guitar's real whammy isn't readable. */
    WHAMMY("Whammy"),

    OFF("Off"),
}

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
    val touchStripMode: TouchStripMode = TouchStripMode.AXIS,
)

/**
 * Turns evdev events from the guitar's input devices into virtual-gamepad
 * reports. Pure logic, no I/O: feed [onEvent], send what it returns.
 */
class GuitarBridge(
    private val mapping: BridgeMapping = BridgeMapping(),
    private val inputs: InputLayout = InputLayout.DEFAULT,
) {
    var state = GuitarInput()
        private set

    private val whammyRange = AutoRange(minimumSpan = 8)
    private val accel = IntArray(3)
    private var tiltButtonDown = false

    /** True while tilt is holding the star power button. */
    val tiltButtonHeld: Boolean get() = tiltButtonDown

    /** True when the touch strip presses frets (see [TouchStripMode.FRETS]). */
    val touchFretsActive: Boolean get() = mapping.touchStripMode == TouchStripMode.FRETS
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
        if (mapping.touchStripMode == TouchStripMode.FRETS) s.touchFrets.forEach { mapping.frets[it]?.let(::add) }
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
        effectiveWhammy()?.let { axes[mapping.whammyAxis.ordinal] = it * 2 - 1 }
        s.tilt?.let { axes[mapping.tiltAxis.ordinal] = it.amount * 2 - 1 }
        if (mapping.touchStripMode == TouchStripMode.AXIS) s.touchStrip?.let { axes[mapping.touchStripAxis.ordinal] = it }
        return VirtualGamepad.report(buttons, hat, axes)
    }

    /** Whammy as sent to the gamepad: the real bar, or the touch strip standing in for it. */
    fun effectiveWhammy(): Float? {
        val s = state
        if (s.whammy != null || mapping.touchStripMode != TouchStripMode.WHAMMY) return s.whammy
        // Released strip = whammy at rest; touching anywhere pushes it, further right pushes more.
        return s.touchStrip?.let { 0.5f + it / 2 } ?: 0f
    }

    private fun onKey(source: GuitarSource, code: Int, down: Boolean) {
        val controls = inputs.forSource(source).filterValues { it.type == EvdevEvent.EV_KEY && it.code == code }.keys
        if (controls.isEmpty()) return
        controls.forEach { setControl(it, down) }
        pending = true
    }

    /** Hat-style axes bound to controls (e.g. the Odin's strum). Returns true if any binding matched. */
    private fun onBoundAxis(source: GuitarSource, code: Int, value: Int): Boolean {
        val bound = inputs.forSource(source).filterValues { it.type == EvdevEvent.EV_ABS && it.code == code }
        bound.forEach { (control, input) -> setControl(control, input.pressed(value)) }
        return bound.isNotEmpty()
    }

    private fun setControl(control: GuitarControl, down: Boolean) {
        val s = state
        val fret = control.fret
        state = when {
            fret != null -> s.copy(frets = if (down) s.frets + fret else s.frets - fret)
            control == GuitarControl.STRUM_UP -> s.copy(strumUp = down)
            control == GuitarControl.STRUM_DOWN -> s.copy(strumDown = down)
            control == GuitarControl.PLUS -> s.copy(plus = down)
            else -> s.copy(minus = down)
        }
    }

    private fun onAbs(source: GuitarSource, code: Int, value: Int) {
        if (source != GuitarSource.ACCELEROMETER && onBoundAxis(source, code, value)) pending = true
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
                Codes.ABS_HAT0X -> state.copy(
                    touchStrip = kernelTouchStrip(value),
                    touchFrets = if (value == TouchStrip.NOT_TOUCHED) emptySet() else TouchStrip.decode(value).frets,
                )
                Codes.ABS_HAT1X -> state.copy(whammy = whammyRange.normalize(value))
                else -> return
            }
            GuitarSource.ODIN_GUITAR -> when (code) {
                Codes.ABS_X -> state.copy(stickX = value / 32767f)
                Codes.ABS_Y -> state.copy(stickY = value / 32767f)
                Codes.ABS_HAT0X -> state.copy(touchStrip = value.toFloat().takeIf { value != 0 })
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
}
