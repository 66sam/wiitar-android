package io.wiitar.bridge

import io.wiitar.protocol.Axis
import io.wiitar.protocol.Fret
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeConfigTest {
    @Test
    fun `config round trips through text`() {
        val config = BridgeConfig(
            buttons = "RED=L2",
            inputs = InputLayout.DEFAULT.with(GuitarSource.ODIN_GUITAR, GuitarControl.GREEN, PhysicalInput.key(Codes.BTN_C)),
            touchStripMode = TouchStripMode.WHAMMY,
            tiltCalibration = TiltCalibration(Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)),
            tiltAxis = Axis.Z,
            tiltInvert = true,
            hideGuitar = false,
        )
        assertEquals(config, BridgeConfig.decode(config.encode()))
        assertEquals(BridgeConfig(), BridgeConfig.decode(""))
        assertEquals(BridgeConfig(), BridgeConfig.decode("nonsense\nhideGuitar\tmaybe"))
    }

    @Test
    fun `mapping applies buttons and touch strip mode`() {
        val mapping = BridgeConfig(buttons = "RED=L2", touchStripMode = TouchStripMode.FRETS).mapping()
        assertEquals(VirtualGamepad.Button.L2, mapping.frets[Fret.RED])
        assertEquals(TouchStripMode.FRETS, mapping.touchStripMode)
    }

    @Test
    fun `live state packs the guitar`() {
        val bridge = GuitarBridge()
        bridge.onEvent(GuitarSource.ODIN_GUITAR, EvdevEvent(EvdevEvent.EV_KEY, Codes.BTN_C, 1))
        bridge.onEvent(GuitarSource.ODIN_GUITAR, EvdevEvent(EvdevEvent.EV_ABS, Codes.ABS_HAT0Y, -1))
        bridge.onEvent(GuitarSource.ODIN_GUITAR, EvdevEvent(EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0))
        val live = LiveState.of(2, bridge, kernelSource = false, touchSeen = false)
        assertEquals(LiveState.SIZE, live.size)
        assertEquals(2, live[LiveState.SLOT])
        assertEquals(1 shl Fret.RED.ordinal, live[LiveState.FRETS])
        assertEquals(-1, live[LiveState.STRUM])
        assertEquals(-1, live[LiveState.TILT])
        assertEquals(-1, live[LiveState.WHAMMY])
        assertEquals(LiveState.NO_TOUCH, live[LiveState.TOUCH])
    }
}

class LatencyStatsTest {
    @Test
    fun `summarises a rolling window`() {
        val stats = LatencyStats(capacity = 100)
        assertEquals(listOf(0L, 0L, 0L, 0L), stats.summary().toList())
        for (i in 1..100) stats.add(i * 10L)
        stats.add(-5) // ignored
        val (n, avg, p95, max) = stats.summary().toList()
        assertEquals(100L, n)
        assertEquals(505L, avg)
        assertEquals(950L, p95)
        assertEquals(1000L, max)
        stats.add(5000) // replaces the oldest sample
        assertEquals(5000L, stats.summary()[3])
    }
}
