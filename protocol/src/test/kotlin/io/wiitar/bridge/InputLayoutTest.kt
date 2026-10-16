package io.wiitar.bridge

import io.wiitar.protocol.Fret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputLayoutTest {
    private fun GuitarBridge.feed(source: GuitarSource, type: Int, code: Int, value: Int): ByteArray? {
        onEvent(source, EvdevEvent(type, code, value))
        return onEvent(source, EvdevEvent(EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0))
    }

    @Test
    fun `capture recognises key presses and odin hat directions only`() {
        val key = EvdevEvent(EvdevEvent.EV_KEY, Codes.BTN_C, 1)
        assertEquals(PhysicalInput.key(Codes.BTN_C), PhysicalInput.fromPress(GuitarSource.ODIN_GUITAR, key))
        assertNull(PhysicalInput.fromPress(GuitarSource.ODIN_GUITAR, key.copy(value = 0)))
        assertNull(PhysicalInput.fromPress(GuitarSource.ODIN_GUITAR, key.copy(value = 2))) // autorepeat
        val strumUp = EvdevEvent(EvdevEvent.EV_ABS, Codes.ABS_HAT0Y, -1)
        assertEquals(PhysicalInput.axis(Codes.ABS_HAT0Y, -1), PhysicalInput.fromPress(GuitarSource.ODIN_GUITAR, strumUp))
        assertNull(PhysicalInput.fromPress(GuitarSource.ODIN_GUITAR, strumUp.copy(value = 0)))
        assertNull(PhysicalInput.fromPress(GuitarSource.ODIN_GUITAR, EvdevEvent(EvdevEvent.EV_ABS, Codes.ABS_X, 30000)))
        // The kernel's touch strip / whammy are analog, never "presses".
        assertNull(PhysicalInput.fromPress(GuitarSource.KERNEL_GUITAR, EvdevEvent(EvdevEvent.EV_ABS, Codes.ABS_HAT0X, 4)))
        assertNull(PhysicalInput.fromPress(GuitarSource.ACCELEROMETER, EvdevEvent(EvdevEvent.EV_KEY, 1, 1)))
    }

    @Test
    fun `reassigning moves the input and round trips`() {
        // Swap red and green on the Odin copy, as a left-handed or odd guitar might need.
        val layout = InputLayout.DEFAULT
            .with(GuitarSource.ODIN_GUITAR, GuitarControl.GREEN, PhysicalInput.key(Codes.BTN_C))
            .with(GuitarSource.ODIN_GUITAR, GuitarControl.RED, PhysicalInput.key(Codes.BTN_EAST))
        val odin = layout.forSource(GuitarSource.ODIN_GUITAR)
        assertEquals(PhysicalInput.key(Codes.BTN_C), odin[GuitarControl.GREEN])
        assertEquals(PhysicalInput.key(Codes.BTN_EAST), odin[GuitarControl.RED])
        assertEquals(layout, InputLayout.decode(layout.encode()))
        assertEquals(InputLayout.DEFAULT, InputLayout.decode("garbage,ODIN_GUITAR.NOPE=1:2:3"))
        // Kernel bindings untouched.
        assertEquals(InputLayout.DEFAULT.forSource(GuitarSource.KERNEL_GUITAR), layout.forSource(GuitarSource.KERNEL_GUITAR))

        val bridge = GuitarBridge(inputs = layout)
        bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_KEY, Codes.BTN_C, 1)
        assertEquals(setOf(Fret.GREEN), bridge.state.frets)
    }

    @Test
    fun `assigning an input already used by another control takes it over`() {
        val layout = InputLayout.DEFAULT.with(GuitarSource.KERNEL_GUITAR, GuitarControl.PLUS, PhysicalInput.key(Codes.BTN_SELECT))
        val kernel = layout.forSource(GuitarSource.KERNEL_GUITAR)
        assertEquals(PhysicalInput.key(Codes.BTN_SELECT), kernel[GuitarControl.PLUS])
        assertNull(kernel[GuitarControl.MINUS])
    }

    @Test
    fun `odin strum comes through the layout`() {
        val bridge = GuitarBridge()
        bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT0Y, 1)
        assertTrue(bridge.state.strumDown && !bridge.state.strumUp)
        bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT0Y, 0)
        assertTrue(!bridge.state.strumDown && !bridge.state.strumUp)
    }

    @Test
    fun `touch strip can press frets alongside the buttons`() {
        val bridge = GuitarBridge(BridgeMapping(touchStripMode = TouchStripMode.FRETS))
        bridge.feed(GuitarSource.KERNEL_GUITAR, EvdevEvent.EV_KEY, Codes.BTN_1, 1) // green button held
        bridge.feed(GuitarSource.KERNEL_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT0X, 0x17) // blue on the strip
        assertEquals(setOf(VirtualGamepad.Button.A, VirtualGamepad.Button.L1), bridge.pressedButtons())
        bridge.feed(GuitarSource.KERNEL_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT0X, 0x0F) // released
        assertEquals(setOf(VirtualGamepad.Button.A), bridge.pressedButtons())
    }

    @Test
    fun `touch strip can stand in for whammy`() {
        val bridge = GuitarBridge(BridgeMapping(touchStripMode = TouchStripMode.WHAMMY))
        assertEquals(0f, bridge.effectiveWhammy())
        bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT0X, 1)
        assertEquals(1f, bridge.effectiveWhammy())
        // A real whammy wins when present.
        val kernel = GuitarBridge(BridgeMapping(touchStripMode = TouchStripMode.WHAMMY))
        kernel.feed(GuitarSource.KERNEL_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT1X, 3)
        assertEquals(0f, kernel.effectiveWhammy())
    }

    @Test
    fun `evdev timestamps decode in both struct sizes`() {
        val e = EvdevEvent(EvdevEvent.EV_KEY, Codes.BTN_C, 1, timeMicros = 1_791_884_216_123_456L)
        assertEquals(e, EvdevEvent.decode(EvdevEvent.encode(e), 0))
        val small = e.copy(timeMicros = 2_000_000_123L)
        assertEquals(small, EvdevEvent.decode(EvdevEvent.encode(small, EvdevEvent.SIZE_32), 0, EvdevEvent.SIZE_32))
    }
}
