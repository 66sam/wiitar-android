package io.wiitar.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuitarDiscoveryTest {
    private fun dev(name: String, vendor: Int, sysfs: String, event: String) =
        KernelInputDevice(name, 5, vendor, if (vendor == 0x057e) 0x0306 else 0x0111, sysfs, listOf(event))

    private fun wiimote(hid: String, base: Int) = listOf(
        dev(GuitarDiscovery.ACCEL_NAME, 0x057e, "/devices/virtual/misc/uhid/$hid/input/input$base", "event$base"),
        dev(GuitarDiscovery.REMOTE_NAME, 0x057e, "/devices/virtual/misc/uhid/$hid/input/input${base + 1}", "event${base + 1}"),
        dev(GuitarDiscovery.GUITAR_NAME, 0x057e, "/devices/virtual/misc/uhid/$hid/input/input${base + 2}", "event${base + 2}"),
    )

    @Test
    fun `odin with one guitar uses the copy and the matching accelerometer`() {
        // As captured on an Odin 3: kernel guitar event13 hidden, copy event14.
        val devices = wiimote("0005:057E:0306.0009", 10) +
            dev(GuitarDiscovery.GUITAR_NAME, 0x2020, "/devices/virtual/input/input32", "event14") +
            dev("Wiitar Guitar 1", 0x1209, "/devices/virtual/misc/uhid/0006:1209:5754.000B/input/input34", "event15")
        val found = GuitarDiscovery.find(devices) { it.eventHandler != "event12" }
        assertEquals(1, found.size)
        val g = found.single()
        assertEquals(GuitarSource.ODIN_GUITAR, g.source)
        assertEquals("event14", g.guitar.eventHandler)
        assertEquals("event10", g.accelerometer?.eventHandler)
        assertEquals("event11", g.remote?.eventHandler)
        assertEquals("/devices/virtual/misc/uhid/0005:057E:0306.0009", g.key)
    }

    @Test
    fun `odin with two guitars pairs copies in creation order`() {
        val devices = wiimote("0005:057E:0306.0009", 10) + wiimote("0005:057E:0306.000C", 20) +
            dev(GuitarDiscovery.GUITAR_NAME, 0x2020, "/devices/virtual/input/input40", "event40") +
            dev(GuitarDiscovery.GUITAR_NAME, 0x2020, "/devices/virtual/input/input32", "event32")
        val hidden = setOf("event12", "event22")
        val found = GuitarDiscovery.find(devices) { it.eventHandler !in hidden }
        assertEquals(listOf("event32", "event40"), found.map { it.guitar.eventHandler })
        assertEquals(listOf("event10", "event20"), found.map { it.accelerometer?.eventHandler })
    }

    @Test
    fun `other devices read the kernel guitar directly`() {
        val found = GuitarDiscovery.find(wiimote("0005:057E:0306.0001", 3)) { true }
        assertEquals(GuitarSource.KERNEL_GUITAR, found.single().source)
        assertEquals("event5", found.single().guitar.eventHandler)
    }

    @Test
    fun `hidden guitar without its copy yet is skipped until the copy appears`() {
        val found = GuitarDiscovery.find(wiimote("0005:057E:0306.0009", 10)) { it.eventHandler != "event12" }
        assertTrue(found.isEmpty())
        val noAccel = GuitarDiscovery.find(wiimote("0005:057E:0306.0009", 10)) { it.eventHandler != "event10" }
        assertNull(noAccel.single().accelerometer)
    }
}
