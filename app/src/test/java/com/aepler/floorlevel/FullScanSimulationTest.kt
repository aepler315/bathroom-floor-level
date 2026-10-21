package com.aepler.floorlevel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Walks a whole simulated floor through the detector, calibration and analysis,
 * with a phone that has its own tilt (camera bump), to check the signs end to end.
 */
class FullScanSimulationTest {
    private val g = PlacementDetector.GRAVITY
    private val rnd = Random(7)
    private var t = 0L

    /** Floor falls 3/16 in per ft toward the door and 1/16 in per ft toward the right wall. */
    private val riseRight = -1.0 / 16
    private val riseDoor = -3.0 / 16
    private val biasX = 0.025
    private val biasY = -0.015

    private fun restingReading(turned: Boolean): Triple<Double, Double, Double> {
        // Accelerometer reads the "up" vector in phone axes. Top at back wall:
        // phone +x = right, phone +y = away from the door.
        var tx = riseRight / 12
        var ty = -riseDoor / 12
        if (turned) { tx = -tx; ty = -ty }
        val n = { rnd.nextDouble(-0.02, 0.02) }
        return Triple((tx + biasX) * g + n(), (ty + biasY) * g + n(), g + n())
    }

    private fun place(d: PlacementDetector, turned: Boolean): PlacementDetector.Event.Measured {
        // Carry it (tilted, shaky), then set it down for 3 s.
        repeat(50) { d.onSample(t, 2.0 + rnd.nextDouble(-2.0, 2.0), 4.0 + rnd.nextDouble(-2.0, 2.0), 8.0, ); t += 20 }
        var m: PlacementDetector.Event.Measured? = null
        repeat(150) {
            val (x, y, z) = restingReading(turned)
            val e = d.onSample(t, x, y, z)
            if (e is PlacementDetector.Event.Measured) m = e
            t += 20
        }
        return m ?: error("no measurement")
    }

    @Test
    fun wholeScanFindsTiltTowardDoor() {
        val scan = ScanState(5, 4)
        val d = PlacementDetector()
        d.expectPlacement()
        while (!scan.isDone) {
            val turned = scan.awaitingTurnedReading
            val m = place(d, turned)
            scan.record(m.x, m.y, m.z)
        }
        for (i in 0 until scan.size) {
            assertEquals(riseRight, scan.riseRight[i], 0.01)
            assertEquals(riseDoor, scan.riseTowardDoor[i], 0.01)
        }
        scan.toggleDrain(scan.index(1, 1))
        scan.toggleDrain(scan.index(2, 2))
        val a = FloorAnalysis(scan)
        val titles = a.findings.map { it.title }
        assertTrue(titles.toString(), titles.contains("Whole floor tilts toward the door"))
        // Back-left corner should sit above the drain, front-right below it.
        assertTrue(a.height[scan.index(0, 0)] > 0)
        assertTrue(a.height[scan.index(4, 3)] < 0)
    }
}
