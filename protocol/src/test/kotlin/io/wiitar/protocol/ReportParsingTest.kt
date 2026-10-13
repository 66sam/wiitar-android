package io.wiitar.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportParsingTest {
    @Test
    fun `output reports match WiiBrew byte layouts`() {
        assertEquals("12 04 35", OutputReports.setReportMode(ReportMode.CORE_ACCEL_EXT16).toHex())
        assertEquals("12 00 32", OutputReports.setReportMode(ReportMode.CORE_EXT8, continuous = false).toHex())
        assertEquals("15 00", OutputReports.requestStatus().toHex())
        assertEquals("11 10", OutputReports.setLeds(OutputReports.playerLedMask(0)).toHex())
        assertEquals("11 80", OutputReports.setLeds(OutputReports.playerLedMask(3)).toHex())
        assertEquals("17 04 a4 00 fa 00 06", OutputReports.readRegister(0xA400FA, 6).toHex())
        assertEquals("17 00 00 00 16 00 0a", OutputReports.readEeprom(0x0016, 10).toHex())
        val write = OutputReports.writeRegister(0xA400F0, bytesOf(0x55))
        assertEquals(22, write.size)
        assertEquals("16 04 a4 00 f0 01 55", write.copyOfRange(0, 7).toHex())
        assertTrue(write.copyOfRange(7, 22).all { it == 0.toByte() })
    }

    @Test
    fun `hid framing adds and strips transaction headers`() {
        assertEquals("a2 15 00", HidFraming.wrapOutput(OutputReports.requestStatus()).toHex())
        assertEquals("30 00 00", HidFraming.unwrapInput(hexBytes("a1 30 00 00")).toHex())
        assertEquals("30 00 00", HidFraming.unwrapInput(hexBytes("30 00 00")).toHex())
    }

    @Test
    fun `status report flags`() {
        // Buttons A held, battery low, extension connected, LED 1, battery 0xC8.
        val r = InputReport.parse(hexBytes("a1 20 00 08 13 00 00 c8"))
        assertIs<InputReport.Status>(r)
        assertTrue(CoreButton.A in r.buttons)
        assertTrue(r.batteryLow)
        assertTrue(r.extensionConnected)
        assertFalse(r.speakerEnabled)
        assertEquals(0b0001, r.ledMask)
        assertEquals(0xC8, r.battery)
    }

    @Test
    fun `read data report carries size, error and address`() {
        val r = InputReport.parse(hexBytes("21 00 00 50 00 fa 00 00 a4 20 01 03" + " 00".repeat(10)))
        assertIs<InputReport.ReadData>(r)
        assertEquals(0, r.error)
        assertEquals(0x00FA, r.addressLow)
        assertEquals("00 00 a4 20 01 03", r.data.toHex())

        val err = InputReport.parse(hexBytes("21 00 00 f8 00 fa" + " 00".repeat(16)))
        assertIs<InputReport.ReadData>(err)
        assertEquals(8, err.error)
    }

    @Test
    fun `ack report`() {
        val r = InputReport.parse(hexBytes("22 00 00 16 00"))
        assertEquals(InputReport.Ack(CoreButtons(0), 0x16, 0), r)
    }

    @Test
    fun `core buttons ignore accelerometer bits`() {
        // Byte0: D-Up (0x08) + accel X LSBs (0x60). Byte1: Home (0x80) + accel Y/Z bits (0x60).
        val b = CoreButtons.parse(hexBytes("68 e0"))
        assertEquals(listOf(CoreButton.DPAD_UP, CoreButton.HOME), b.pressed)
    }

    @Test
    fun `accelerometer 10-bit assembly`() {
        // Accel bytes 0x80 0x81 0x9a; X LSBs = 0b11, Y bit1 set, Z bit1 clear.
        val r = InputReport.parse(hexBytes("31 60 20 80 81 9a"))
        assertIs<InputReport.Data>(r)
        assertEquals(AccelRaw(0x80 * 4 + 3, 0x81 * 4 + 2, 0x9a * 4), r.accel)
    }

    @Test
    fun `mode 0x35 splits buttons, accel and 16 extension bytes`() {
        val ext = (0 until 16).joinToString(" ") { "%02x".format(it) }
        val r = InputReport.parse(hexBytes("a1 35 00 00 80 80 80 $ext"))
        assertIs<InputReport.Data>(r)
        assertEquals(ReportMode.CORE_ACCEL_EXT16, r.mode)
        assertEquals(16, r.extension!!.size)
        assertEquals(0x0F, r.extension!!.last().toInt())
    }

    @Test
    fun `mode 0x32 carries 8 extension bytes and no accel`() {
        val r = InputReport.parse(hexBytes("32 00 00 1f 1f 0f 10 ff ff 00 00"))
        assertIs<InputReport.Data>(r)
        assertNull(r.accel)
        assertEquals("1f 1f 0f 10 ff ff 00 00", r.extension!!.toHex())
    }

    @Test
    fun `mode 0x3d has no core buttons`() {
        val r = InputReport.parse(hexBytes("3d" + " 11".repeat(21)))
        assertIs<InputReport.Data>(r)
        assertNull(r.buttons)
        assertEquals(21, r.extension!!.size)
    }

    @Test
    fun `truncated reports are rejected`() {
        assertNull(InputReport.parse(hexBytes("35 00 00 80")))
        assertNull(InputReport.parse(ByteArray(0)))
        assertIs<InputReport.Unknown>(InputReport.parse(hexBytes("5a 01")))
    }

    @Test
    fun `extension id table`() {
        assertEquals(ExtensionType.GUITAR, ExtensionType.fromId(hexBytes("0000a4200103")))
        assertEquals(ExtensionType.DRUMS, ExtensionType.fromId(hexBytes("0100a4200103")))
        assertEquals(ExtensionType.UNKNOWN, ExtensionType.fromId(hexBytes("1234a4200103")))
        assertTrue(ExtensionType.isNotReady(hexBytes("ffffffffffff")))
        assertTrue(ExtensionType.isNotReady(hexBytes("000000000000")))
    }

    @Test
    fun `extension calibration checksum`() {
        val body = ByteArray(14) { (it * 7).toByte() }
        val sum = body.sumOf { it.toInt() and 0xFF }
        val good = body + bytesOf(sum + 0x55, sum + 0xAA)
        assertTrue(ExtensionCalibration(good).checksumValid)
        assertFalse(ExtensionCalibration(ByteArray(16) { 0xFF.toByte() }).checksumValid)
    }

    @Test
    fun `hex helpers round trip`() {
        assertContentEquals(bytesOf(0xA1, 0x30), hexBytes("a1 30"))
        assertEquals("a1 30", bytesOf(0xA1, 0x30).toHex())
    }
}
