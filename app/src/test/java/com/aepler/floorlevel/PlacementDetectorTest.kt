package com.aepler.floorlevel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PlacementDetectorTest {
    private val g = PlacementDetector.GRAVITY
    private val rnd = Random(1)
    private var t = 0L

    /** Feed [ms] of 50 Hz samples; returns every event emitted. */
    private fun feed(d: PlacementDetector, ms: Long, sample: () -> Triple<Double, Double, Double>): List<PlacementDetector.Event> {
        val events = mutableListOf<PlacementDetector.Event>()
        val end = t + ms
        while (t < end) {
            val (x, y, z) = sample()
            d.onSample(t, x, y, z)?.let { events += it }
            t += 20
        }
        return events
    }

    private fun noise(s: Double) = rnd.nextDouble(-s, s)
    private fun inHand() = Triple(3.0 + noise(1.5), 5.0 + noise(1.5), 7.5 + noise(1.5))
    private fun resting(tiltX: Double) = Triple(g * sin(tiltX) + noise(0.02), noise(0.02), g * cos(tiltX) + noise(0.02))

    @Test
    fun measuresAfterBeingSetDownStill() {
        val d = PlacementDetector()
        d.expectPlacement()
        assertTrue(feed(d, 1000) { inHand() }.isEmpty())
        val ev = feed(d, 3000) { resting(0.01) }
        assertTrue(ev.first() is PlacementDetector.Event.MeasuringStarted)
        val m = ev.filterIsInstance<PlacementDetector.Event.Measured>().single()
        assertEquals(0.01, m.x / m.z, 0.001)
        assertEquals(PlacementDetector.Phase.WAITING_FOR_LIFT, d.phase)
    }

    @Test
    fun screenTapIsNotALift() {
        val d = PlacementDetector()
        d.expectPlacement()
        feed(d, 3000) { resting(0.0) }
        var spikes = 2
        val ev = feed(d, 1000) {
            if (spikes-- > 0) Triple(0.5, 0.5, g + 3.0) else resting(0.0)
        }
        assertTrue(ev.isEmpty())
    }

    @Test
    fun pickingUpThenSettingDownGivesSecondMeasurement() {
        val d = PlacementDetector()
        d.expectPlacement()
        feed(d, 3000) { resting(0.0) }
        val ev = feed(d, 1500) { inHand() } + feed(d, 3000) { resting(-0.02) }
        assertTrue(ev.first() is PlacementDetector.Event.Lifted)
        val m = ev.filterIsInstance<PlacementDetector.Event.Measured>().single()
        assertEquals(-0.02, m.x / m.z, 0.001)
    }

    @Test
    fun bumpWhileMeasuringRestarts() {
        val d = PlacementDetector()
        d.expectPlacement()
        val ev = feed(d, 1000) { resting(0.0) } + feed(d, 40) { Triple(1.0, 1.0, g) } + feed(d, 3000) { resting(0.0) }
        assertTrue(ev.contains(PlacementDetector.Event.Disturbed))
        assertNotNull(ev.filterIsInstance<PlacementDetector.Event.Measured>().singleOrNull())
    }

    @Test
    fun pauseDuringMeasurementStartsOver() {
        val d = PlacementDetector()
        d.expectPlacement()
        val ev = feed(d, 1200) { resting(0.0) }
        assertTrue(ev.contains(PlacementDetector.Event.MeasuringStarted))
        d.abortMeasurement()
        t += 10_000  // app was paused
        // The first sample after resuming must not complete the old measurement.
        assertTrue(feed(d, 100) { resting(0.0) }.none { it is PlacementDetector.Event.Measured })
        assertEquals(PlacementDetector.Phase.MOVING, d.phase)
        assertNotNull(feed(d, 3000) { resting(0.0) }.filterIsInstance<PlacementDetector.Event.Measured>().singleOrNull())
    }

    @Test
    fun tiltedInHandIsNeverMeasured() {
        val d = PlacementDetector()
        d.expectPlacement()
        // Perfectly still but held at ~35 degrees: not lying on the floor.
        assertTrue(feed(d, 4000) { Triple(0.0, g * sin(0.6), g * cos(0.6)) }.isEmpty())
    }
}
