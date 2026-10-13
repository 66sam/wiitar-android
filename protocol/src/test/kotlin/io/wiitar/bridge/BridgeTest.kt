package io.wiitar.bridge

import io.wiitar.protocol.Fret
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeTest {
    /** Excerpt of /proc/bus/input/devices captured on an AYN Odin 3 with a guitar connected. */
    private val odinDevices = """
        I: Bus=0005 Vendor=057e Product=0306 Version=0600
        N: Name="Nintendo Wii Remote Accelerometer"
        P: Phys=
        S: Sysfs=/devices/virtual/misc/uhid/0005:057E:0306.0009/input/input29
        U: Uniq=
        H: Handlers=event10
        B: PROP=0
        B: EV=9
        B: ABS=38

        I: Bus=0005 Vendor=057e Product=0306 Version=0600
        N: Name="Nintendo Wii Remote Guitar"
        P: Phys=
        S: Sysfs=/devices/virtual/misc/uhid/0005:057E:0306.0009/input/input31
        U: Uniq=
        H: Handlers=event13 cpufreq
        B: PROP=0
        B: EV=b
        B: ABS=50003

        I: Bus=0003 Vendor=2020 Product=0111 Version=0064
        N: Name="Nintendo Wii Remote Guitar"
        P: Phys=
        S: Sysfs=/devices/virtual/input/input32
        U: Uniq=
        H: Handlers=event14 cpufreq
        B: PROP=0
        B: EV=b
    """.trimIndent()

    @Test
    fun `parses proc input devices`() {
        val devices = KernelInputDevice.parseAll(odinDevices)
        assertEquals(3, devices.size)
        val accel = devices[0]
        assertEquals("Nintendo Wii Remote Accelerometer", accel.name)
        assertEquals(0x057e, accel.vendor)
        assertEquals("event10", accel.eventHandler)
        assertEquals("/devices/virtual/misc/uhid/0005:057E:0306.0009", accel.hidParent)
        assertEquals(devices[1].hidParent, accel.hidParent)
        assertEquals("event13", devices[1].eventHandler)
        assertEquals(0x2020, devices[2].vendor)
        assertEquals(listOf("event14", "cpufreq"), devices[2].handlers)
    }

    @Test
    fun `evdev events round trip in both struct sizes`() {
        val e = EvdevEvent(EvdevEvent.EV_ABS, Codes.ABS_HAT1X, -7)
        assertEquals(e, EvdevEvent.decode(EvdevEvent.encode(e), 0))
        assertEquals(e, EvdevEvent.decode(EvdevEvent.encode(e, EvdevEvent.SIZE_32), 0, EvdevEvent.SIZE_32))
        val two = EvdevEvent.encode(EvdevEvent(1, 2, 3)) + EvdevEvent.encode(e)
        assertEquals(e, EvdevEvent.decode(two, EvdevEvent.SIZE_64))
    }

    @Test
    fun `uhid create2 layout`() {
        val msg = Uhid.create2("Wiitar Guitar 1", VirtualGamepad.DESCRIPTOR, 0x1209, 0x5754)
        assertEquals(Uhid.EVENT_SIZE, msg.size)
        val b = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Uhid.UHID_CREATE2, b.getInt(0))
        assertEquals("Wiitar Guitar 1", String(msg, 4, 15))
        assertEquals(0, msg[4 + 15].toInt())
        val after = 4 + 128 + 64 + 64
        assertEquals(VirtualGamepad.DESCRIPTOR.size, b.getShort(after).toInt())
        assertEquals(Uhid.BUS_VIRTUAL, b.getShort(after + 2).toInt())
        assertEquals(0x1209, b.getInt(after + 4))
        assertEquals(0x5754, b.getInt(after + 8))
        assertEquals(VirtualGamepad.DESCRIPTOR.toList(), msg.copyOfRange(after + 20, after + 20 + VirtualGamepad.DESCRIPTOR.size).toList())
    }

    @Test
    fun `uhid input2 layout`() {
        val msg = Uhid.input2(byteArrayOf(1, 2, 3))
        val b = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Uhid.UHID_INPUT2, b.getInt(0))
        assertEquals(3, b.getShort(4).toInt())
        assertEquals(listOf<Byte>(1, 2, 3), msg.copyOfRange(6, 9).toList())
    }

    @Test
    fun `descriptor declares a 15-byte report`() {
        // Bits: 15 buttons + 1 pad + 4 hat + 4 pad + 6 x 16 axes = 120 = 15 bytes.
        assertEquals(15, VirtualGamepad.REPORT_SIZE)
        assertEquals(0xC0.toByte(), VirtualGamepad.DESCRIPTOR.last())
    }

    @Test
    fun `gamepad report encodes buttons hat and axes`() {
        val axes = FloatArray(6).also { it[VirtualGamepad.Axis.Z.ordinal] = 1f; it[VirtualGamepad.Axis.X.ordinal] = -1f }
        val r = VirtualGamepad.report(setOf(VirtualGamepad.Button.A, VirtualGamepad.Button.L1), VirtualGamepad.Hat.DOWN, axes)
        val b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0b0100_0001, b.getShort(0).toInt())
        assertEquals(4, r[2].toInt())
        assertEquals(-32767, b.getShort(3).toInt()) // X
        assertEquals(32767, b.getShort(7).toInt()) // Z
        assertEquals(8, VirtualGamepad.report(emptySet(), VirtualGamepad.Hat.NONE, FloatArray(6))[2].toInt())
    }

    private fun GuitarBridge.feed(source: GuitarSource, type: Int, code: Int, value: Int): ByteArray? {
        onEvent(source, EvdevEvent(type, code, value))
        return onEvent(source, EvdevEvent(EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0))
    }

    @Test
    fun `odin guitar frets map to Xbox-style buttons`() {
        val bridge = GuitarBridge()
        val expected = mapOf(
            Codes.BTN_EAST to VirtualGamepad.Button.A,
            Codes.BTN_C to VirtualGamepad.Button.B,
            Codes.BTN_NORTH to VirtualGamepad.Button.Y,
            Codes.BTN_WEST to VirtualGamepad.Button.X,
            Codes.BTN_Z to VirtualGamepad.Button.L1,
        )
        for ((code, button) in expected) {
            val down = assertNotNull(bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_KEY, code, 1))
            assertEquals(1 shl (button.hidIndex - 1), ByteBuffer.wrap(down).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt())
            bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_KEY, code, 0)
        }
    }

    @Test
    fun `kernel guitar frets, strum and whammy`() {
        val bridge = GuitarBridge()
        bridge.feed(GuitarSource.KERNEL_GUITAR, EvdevEvent.EV_KEY, Codes.BTN_1, 1)
        assertEquals(setOf(Fret.GREEN), bridge.state.frets)
        val strum = assertNotNull(bridge.feed(GuitarSource.KERNEL_GUITAR, EvdevEvent.EV_KEY, Codes.BTN_DPAD_DOWN, 1))
        assertEquals(4, strum[2].toInt())
        bridge.feed(GuitarSource.KERNEL_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT1X, 0) // rest
        assertEquals(0f, bridge.state.whammy)
        val r = assertNotNull(bridge.feed(GuitarSource.KERNEL_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT1X, 8))
        assertEquals(1f, bridge.state.whammy)
        assertEquals(32767, ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).getShort(3 + 2 * VirtualGamepad.Axis.Z.ordinal).toInt())
    }

    @Test
    fun `odin strum hat and unchanged frames`() {
        val bridge = GuitarBridge()
        assertNotNull(bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT0Y, -1))
        assertTrue(bridge.state.strumUp)
        // Same state again: no new report.
        assertNull(bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT0Y, -1))
        bridge.feed(GuitarSource.ODIN_GUITAR, EvdevEvent.EV_ABS, Codes.ABS_HAT0Y, 0)
        assertTrue(!bridge.state.strumUp && !bridge.state.strumDown)
    }

    @Test
    fun `accelerometer tilt drives axis and star power button`() {
        val bridge = GuitarBridge() // tilt axis Y, 0..45 degrees, button at 0.6
        // Level: gravity on Z (~ +104 counts per g with the default calibration).
        bridge.onEvent(GuitarSource.ACCELEROMETER, EvdevEvent(EvdevEvent.EV_ABS, Codes.ABS_RZ, 104))
        bridge.feed(GuitarSource.ACCELEROMETER, EvdevEvent.EV_ABS, Codes.ABS_RY, 0)
        assertEquals(0f, bridge.state.tilt!!.amount)
        // Neck straight up: gravity on Y.
        bridge.onEvent(GuitarSource.ACCELEROMETER, EvdevEvent(EvdevEvent.EV_ABS, Codes.ABS_RZ, 0))
        val r = assertNotNull(bridge.feed(GuitarSource.ACCELEROMETER, EvdevEvent.EV_ABS, Codes.ABS_RY, 104))
        assertEquals(1f, bridge.state.tilt!!.amount)
        val buttons = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt()
        assertEquals(1 shl (VirtualGamepad.Button.R1.hidIndex - 1), buttons)
    }
}
