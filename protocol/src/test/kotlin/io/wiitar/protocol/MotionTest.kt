package io.wiitar.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionTest {
    @Test
    fun `parses eeprom calibration with lsb bits`() {
        // zero X=0x80 + LSB 0b10, Y=0x81 + 0b01, Z=0x82 + 0b11 -> lsb byte 0b00_10_01_11
        val body = bytesOf(0x80, 0x81, 0x82, 0x27, 0x9a, 0x9b, 0x9c, 0x00, 0x40)
        val data = body + bytesOf(body.sumOf { it.toInt() and 0xFF } + 0x55)
        val cal = AccelCalibration.parse(data)
        assertTrue(cal.checksumValid)
        assertEquals(AccelRaw(0x80 * 4 + 2, 0x81 * 4 + 1, 0x82 * 4 + 3), cal.zero)
        assertEquals(AccelRaw(0x9a * 4, 0x9b * 4, 0x9c * 4), cal.oneG)

        data[0] = 0
        assertFalse(AccelCalibration.parse(data).checksumValid)
    }

    @Test
    fun `tilt from gravity direction`() {
        val cal = AccelCalibration.DEFAULT // zero 512, 1g 616
        val config = TiltConfig(axis = Axis.Y, restDegrees = 0f, fullDegrees = 45f)
        val level = TiltCalculator.compute(AccelRaw(512, 512, 616), cal, config)
        assertEquals(0f, level.degrees, 0.01f)
        assertEquals(0f, level.amount)

        val up = TiltCalculator.compute(AccelRaw(512, 616, 512), cal, config)
        assertEquals(90f, up.degrees, 0.01f)
        assertEquals(1f, up.amount)

        // 30 degrees: y = sin30 = 0.5g, z = cos30 ~ 0.866g
        val thirty = TiltCalculator.compute(AccelRaw(512, 512 + 52, 512 + 90), cal, config)
        assertEquals(30f, thirty.degrees, 0.5f)
        assertEquals(0.667f, thirty.amount, 0.02f)

        val inverted = TiltCalculator.compute(AccelRaw(512, 616, 512), cal, config.copy(invert = true))
        assertEquals(-90f, inverted.degrees, 0.01f)
        assertEquals(0f, inverted.amount)
    }
}
