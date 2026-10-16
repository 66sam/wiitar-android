package io.wiitar.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [WiimoteSession] through a full connect / extension hot-plug cycle
 * with a scripted fake Wiimote.
 */
class WiimoteSessionTest {
    private val sent = mutableListOf<String>()
    private val events = mutableListOf<WiimoteEvent>()
    private val session = WiimoteSession(send = { sent += it.toHex() }, emit = { events += it })

    private inline fun <reified T : WiimoteEvent> eventsOf() = events.filterIsInstance<T>()
    private fun feed(hex: String) = session.onInputReport(hexBytes(hex))
    private fun lastSent() = sent.last()

    private fun readReply(addressLow: Int, data: String, error: Int = 0): String {
        val bytes = hexBytes(data)
        val padded = bytes + ByteArray(16 - bytes.size)
        val se = ((bytes.size - 1) shl 4) or error
        return "a1 21 00 00 %02x %02x %02x ".format(se, addressLow shr 8, addressLow and 0xFF) + padded.toHex()
    }

    private val accelCal: String = run {
        // zero = 0x80 (<<2 = 512), one-g = 0x9a (<<2 = 616), no LSBs.
        val b = bytesOf(0x80, 0x80, 0x80, 0x00, 0x9a, 0x9a, 0x9a, 0x00, 0x40)
        (b + bytesOf(b.sumOf { it.toInt() and 0xFF } + 0x55)).toHex()
    }

    private fun connectWithGuitar() {
        session.start(playerSlot = 1)
        assertEquals(
            listOf("11 20", "17 00 00 00 16 00 0a", "15 00"),
            sent,
            "LEDs, accel calibration read, status request",
        )
        feed(readReply(0x0016, accelCal))
        assertTrue(eventsOf<WiimoteEvent.AccelCalibrationRead>().single().calibration.checksumValid)

        // Status: extension connected.
        feed("a1 20 00 00 22 00 00 c0")
        assertEquals(WiimoteSession.ExtensionState.INITIALIZING, session.extensionState)
        assertTrue(sent.contains("16 04 a4 00 f0 01 55" + " 00".repeat(15)))
        assertEquals("12 04 31", lastSent(), "core+accel while extension initialises")

        feed("a1 22 00 00 16 00")
        assertTrue(lastSent().startsWith("16 04 a4 00 fb 01 00"))
        feed("a1 22 00 00 16 00")
        assertEquals("17 04 a4 00 fa 00 06", lastSent())
        feed(readReply(0x00FA, "00 00 a4 20 01 03"))
        assertEquals(ExtensionType.GUITAR, eventsOf<WiimoteEvent.ExtensionConnected>().last().type)
        assertEquals("17 04 a4 00 20 00 10", lastSent())
        feed(readReply(0x0020, (0 until 16).joinToString(" ") { "ff" }))

        assertEquals(WiimoteSession.ExtensionState.READY, session.extensionState)
        assertEquals("12 04 35", lastSent())
        assertEquals(ReportMode.CORE_ACCEL_EXT16, session.reportMode)
        assertNotNull(session.extensionCalibration)
    }

    @Test
    fun `full handshake reads calibration then decodes guitar input`() {
        connectWithGuitar()
        // Green + strum down, accelerometer at rest (Z = +1g).
        feed("a1 35 00 00 80 80 9a e0 e0 ef f0 bf ef" + " 00".repeat(10))
        val input = eventsOf<WiimoteEvent.Input>().last()
        val guitar = assertNotNull(input.guitar)
        assertEquals(setOf(Fret.GREEN), guitar.frets)
        assertTrue(guitar.strumDown)
        assertEquals(0f, assertNotNull(input.tilt).degrees, 0.01f)
    }

    @Test
    fun `unplug and replug re-reads extension`() {
        connectWithGuitar()
        sent.clear()

        feed("a1 20 00 00 00 00 00 c0")
        assertEquals(1, eventsOf<WiimoteEvent.ExtensionDisconnected>().size)
        assertEquals(WiimoteSession.ExtensionState.NONE, session.extensionState)
        assertEquals("12 04 31", lastSent())
        // Data reports now carry no guitar.
        feed("a1 31 00 00 80 80 9a")
        assertNull(eventsOf<WiimoteEvent.Input>().last().guitar)

        feed("a1 20 00 00 02 00 00 c0")
        assertEquals(WiimoteSession.ExtensionState.INITIALIZING, session.extensionState)
        feed("a1 22 00 00 16 00")
        feed("a1 22 00 00 16 00")
        feed(readReply(0x00FA, "00 00 a4 20 01 03"))
        feed(readReply(0x0020, "00".repeat(16)))
        assertEquals(WiimoteSession.ExtensionState.READY, session.extensionState)
        assertEquals(2, eventsOf<WiimoteEvent.ExtensionConnected>().size)
    }

    @Test
    fun `unplug mid-setup ignores stale answers`() {
        session.start(0)
        feed(readReply(0x0016, accelCal))
        feed("a1 20 00 00 02 00 00 c0")
        feed("a1 22 00 00 16 00") // INIT_1 acked, INIT_2 in flight
        feed("a1 20 00 00 00 00 00 c0") // unplugged
        feed("a1 22 00 00 16 00") // late ack for INIT_2
        assertEquals(WiimoteSession.ExtensionState.NONE, session.extensionState)
        assertTrue(!session.hasPendingCommand)
        assertTrue(eventsOf<WiimoteEvent.ExtensionConnected>().isEmpty())
    }

    @Test
    fun `half-inserted plug is retried then reported`() {
        session.start(0)
        feed(readReply(0x0016, accelCal))
        feed("a1 20 00 00 02 00 00 c0")
        feed("a1 22 00 00 16 00")
        feed("a1 22 00 00 16 00")
        repeat(4) { feed(readReply(0x00FA, "ff ff ff ff ff ff")) }
        assertEquals(WiimoteSession.ExtensionState.FAILED, session.extensionState)
        assertIs<WiimoteEvent.ExtensionError>(eventsOf<WiimoteEvent.ExtensionError>().single())
    }

    @Test
    fun `unknown extension is reported with its id`() {
        session.start(0)
        feed(readReply(0x0016, accelCal))
        feed("a1 20 00 00 02 00 00 c0")
        feed("a1 22 00 00 16 00")
        feed("a1 22 00 00 16 00")
        feed(readReply(0x00FA, "12 34 a4 20 01 03"))
        val connected = eventsOf<WiimoteEvent.ExtensionConnected>().single()
        assertEquals(ExtensionType.UNKNOWN, connected.type)
        assertEquals("12 34 a4 20 01 03", connected.id.toHex())
    }

    @Test
    fun `timeouts resend then give up`() {
        session.start(0)
        val read = "17 00 00 00 16 00 0a"
        session.onCommandTimeout()
        session.onCommandTimeout()
        assertEquals(3, sent.count { it == read })
        session.onCommandTimeout()
        assertTrue(!session.hasPendingCommand)
        assertEquals(AccelCalibration.DEFAULT, session.accelCalibration)
    }

    @Test
    fun `diagnostic memory read`() {
        session.start(0)
        feed(readReply(0x0016, accelCal))
        session.readMemory(0xA400FA, 6)
        assertEquals("17 04 a4 00 fa 00 06", lastSent())
        feed(readReply(0x00FA, "00 00 a4 20 01 03"))
        val r = eventsOf<WiimoteEvent.MemoryRead>().single()
        assertEquals(0, r.error)
        assertEquals("00 00 a4 20 01 03", r.data.toHex())
    }

    @Test
    fun `raw traffic is echoed for diagnostics`() {
        session.start(0)
        feed("a1 30 00 08")
        assertEquals(3, eventsOf<WiimoteEvent.RawOutput>().size)
        assertEquals("a1 30 00 08", eventsOf<WiimoteEvent.RawInput>().single().bytes.toHex())
    }
}
