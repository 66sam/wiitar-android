package io.wiitar.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuitarTest {
    /** Builds the 6 guitar bytes. Buttons are given as "pressed" and encoded active-low. */
    private fun guitarBytes(
        stickX: Int = 0x20,
        stickY: Int = 0x20,
        strip: Int = 0x0F,
        whammy: Int = 0x10,
        frets: Set<Fret> = emptySet(),
        strumUp: Boolean = false,
        strumDown: Boolean = false,
        plus: Boolean = false,
        minus: Boolean = false,
    ): ByteArray {
        var b4 = 0xFF
        var b5 = 0xFF
        if (strumDown) b4 = b4 and (1 shl 6).inv()
        if (minus) b4 = b4 and (1 shl 4).inv()
        if (plus) b4 = b4 and (1 shl 2).inv()
        if (strumUp) b5 = b5 and 1.inv()
        val fretBits = mapOf(Fret.ORANGE to 7, Fret.RED to 6, Fret.BLUE to 5, Fret.GREEN to 4, Fret.YELLOW to 3)
        for (f in frets) b5 = b5 and (1 shl fretBits.getValue(f)).inv()
        // Unused high bits are set on real hardware; make sure we mask them.
        return bytesOf(0xC0 or stickX, 0xC0 or stickY, 0xE0 or strip, 0xE0 or whammy, b4, b5)
    }

    @Test
    fun `idle guitar has nothing pressed`() {
        val s = GuitarDecoder().decode(guitarBytes())
        assertTrue(s.frets.isEmpty())
        assertFalse(s.strumUp || s.strumDown || s.plus || s.minus)
        assertEquals(0f, s.stickX)
        assertEquals(0f, s.whammy)
    }

    @Test
    fun `each fret decodes independently`() {
        val d = GuitarDecoder()
        for (fret in Fret.entries) {
            assertEquals(setOf(fret), d.decode(guitarBytes(frets = setOf(fret))).frets, "fret $fret")
        }
        assertEquals(Fret.entries.toSet(), d.decode(guitarBytes(frets = Fret.entries.toSet())).frets)
    }

    @Test
    fun `strum and plus minus`() {
        val d = GuitarDecoder()
        assertTrue(d.decode(guitarBytes(strumUp = true)).strumUp)
        assertTrue(d.decode(guitarBytes(strumDown = true)).strumDown)
        val pm = d.decode(guitarBytes(plus = true, minus = true))
        assertTrue(pm.plus && pm.minus)
        assertFalse(pm.strumUp || pm.strumDown)
    }

    @Test
    fun `whammy auto-ranges from rest position`() {
        val d = GuitarDecoder()
        assertEquals(0f, d.decode(guitarBytes(whammy = 0x10)).whammy)
        assertEquals(0.5f, d.decode(guitarBytes(whammy = 0x15)).whammy)
        assertEquals(1f, d.decode(guitarBytes(whammy = 0x1A)).whammy)
        // Pressing further than the initial guess widens the range.
        assertEquals(1f, d.decode(guitarBytes(whammy = 0x1C)).whammy)
        assertEquals(0.5f, d.decode(guitarBytes(whammy = 0x16)).whammy)
        assertEquals(0x10, d.whammyRestRaw)
        assertEquals(0x1C, d.whammyMaxRaw)
    }

    @Test
    fun `stick normalises around centre`() {
        val s = GuitarDecoder().decode(guitarBytes(stickX = 0x3F, stickY = 0x00))
        assertEquals(1f, s.stickX)
        assertEquals(-1f, s.stickY)
    }

    @Test
    fun `touch strip hidden until it has been seen idle and touched`() {
        val d = GuitarDecoder()
        assertNull(d.decode(guitarBytes(strip = 0x0F)).touchStrip)
        val touched = d.decode(guitarBytes(strip = 0x0A)).touchStrip
        assertEquals(setOf(Fret.RED), touched?.frets)
        assertTrue(d.hasTouchStrip)
        assertEquals(TouchStrip(0x0F, emptySet(), null), d.decode(guitarBytes(strip = 0x0F)).touchStrip)
    }

    @Test
    fun `guitar without strip reporting a constant value never gets one`() {
        val d = GuitarDecoder()
        repeat(50) { assertNull(d.decode(guitarBytes(strip = 0x1F)).touchStrip) }
        assertFalse(d.hasTouchStrip)
    }

    @Test
    fun `touch strip bands follow WiiBrew table`() {
        val expected = mapOf(
            0x04 to setOf(Fret.GREEN),
            0x07 to setOf(Fret.GREEN, Fret.RED),
            0x0A to setOf(Fret.RED),
            0x0C to setOf(Fret.RED, Fret.YELLOW),
            0x0D to setOf(Fret.RED, Fret.YELLOW),
            0x12 to setOf(Fret.YELLOW),
            0x13 to setOf(Fret.YELLOW),
            0x14 to setOf(Fret.YELLOW, Fret.BLUE),
            0x15 to setOf(Fret.YELLOW, Fret.BLUE),
            0x17 to setOf(Fret.BLUE),
            0x18 to setOf(Fret.BLUE),
            0x1A to setOf(Fret.BLUE, Fret.ORANGE),
            0x1F to setOf(Fret.ORANGE),
        )
        for ((raw, frets) in expected) assertEquals(frets, TouchStrip.decode(raw).frets, "0x%02x".format(raw))
        assertEquals(0f, TouchStrip.decode(0x04).position)
        assertEquals(1f, TouchStrip.decode(0x1F).position)
    }
}
