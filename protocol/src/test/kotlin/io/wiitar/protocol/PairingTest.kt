package io.wiitar.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingTest {
    @Test
    fun `pin is the address byte-reversed`() {
        assertEquals("cc bb aa 32 1f 00", WiimotePairing.pinFromAddress("00:1F:32:AA:BB:CC").toHex())
        assertEquals("cc bb aa 32 1f 00", WiimotePairing.pinFromAddress("001f32aabbcc").toHex())
        assertEquals("cc bb aa 32 1f 00", WiimotePairing.pinFromAddress(" 00-1f-32-aa-bb-cc ").toHex())
    }

    @Test
    fun `pairing method picks whose address to use`() {
        val wiimote = "00:1F:32:00:00:01"
        val host = "AA:BB:CC:DD:EE:FF"
        assertEquals("01 00 00 32 1f 00", WiimotePairing.pinFor(WiimotePairing.Method.BUTTONS_1_2, wiimote, null).toHex())
        assertEquals("ff ee dd cc bb aa", WiimotePairing.pinFor(WiimotePairing.Method.SYNC_BUTTON, wiimote, host).toHex())
        assertFailsWith<IllegalArgumentException> { WiimotePairing.pinFor(WiimotePairing.Method.SYNC_BUTTON, wiimote, null) }
    }

    @Test
    fun `address validation`() {
        assertTrue(WiimotePairing.isValidAddress("00:1f:32:aa:bb:cc"))
        assertFalse(WiimotePairing.isValidAddress("00:1f:32:aa:bb"))
        assertFalse(WiimotePairing.isValidAddress("zz:1f:32:aa:bb:cc"))
        assertEquals("00:1F:32:AA:BB:CC", WiimotePairing.normalizeAddress("001f32aabbcc"))
    }

    @Test
    fun `wiimote names`() {
        assertTrue(WiimotePairing.isWiimoteName("Nintendo RVL-CNT-01"))
        assertTrue(WiimotePairing.isWiimoteName("Nintendo RVL-CNT-01-TR"))
        assertFalse(WiimotePairing.isWiimoteName("Pro Controller"))
        assertFalse(WiimotePairing.isWiimoteName(null))
    }
}
