package com.aepler.floorlevel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStateTest {
    private val g = 9.8

    /** Reading for a floor rising [right]/[door] in/ft, phone bias (bx, by), top at back wall. */
    private fun reading(right: Double, door: Double, bx: Double, by: Double, turned: Boolean): Triple<Double, Double, Double> {
        var tx = right / 12
        var ty = -door / 12
        if (turned) { tx = -tx; ty = -ty }
        return Triple((tx + bx) * g, (ty + by) * g, g)
    }

    @Test
    fun snakeOrder() {
        val s = ScanState(3, 3)
        val order = (0 until 9).map { s.cellAtStep(it) }
        assertEquals(listOf(0, 1, 2, 5, 4, 3, 6, 7, 8), order)
    }

    @Test
    fun calibrationRemovesPhoneBias() {
        val s = ScanState(2, 2)
        val bx = 0.03; val by = -0.02   // ~1.7 degrees of camera-bump tilt
        val (ax, ay, az) = reading(0.2, -0.1, bx, by, false)
        assertEquals(ScanState.Outcome.TURN_PHONE_AROUND, s.record(ax, ay, az))
        val (bx2, by2, bz2) = reading(0.2, -0.1, bx, by, true)
        assertEquals(ScanState.Outcome.RECORDED, s.record(bx2, by2, bz2))
        assertEquals(0.2, s.riseRight[0], 1e-9)
        assertEquals(-0.1, s.riseTowardDoor[0], 1e-9)
        val (cx, cy, cz) = reading(-0.25, 0.3, bx, by, false)
        s.record(cx, cy, cz)
        assertEquals(-0.25, s.riseRight[1], 1e-9)
        assertEquals(0.3, s.riseTowardDoor[1], 1e-9)
    }

    @Test
    fun skipThenRedoAndCalibrationReset() {
        val s = ScanState(2, 2)
        s.skip()
        assertTrue(s.skipped[0])
        s.record(0.0, 0.0, g); s.record(0.0, 0.0, g)
        assertTrue(s.isCalibrated)
        assertEquals(1, s.calibrationCell)
        assertTrue(s.redoLast())
        assertFalse(s.isCalibrated)
        assertTrue(s.redoLast())
        assertFalse(s.skipped[0])
        assertEquals(0, s.step)
    }

    @Test
    fun halfTurnCheck() {
        assertTrue(ScanState.isHalfTurn(10.0, -170.0))
        assertTrue(ScanState.isHalfTurn(90.0, -90.0))
        assertTrue(ScanState.isHalfTurn(-30.0, 140.0))   // 170 degrees, close enough
        assertFalse(ScanState.isHalfTurn(10.0, 15.0))     // not turned
        assertFalse(ScanState.isHalfTurn(170.0, -175.0))  // 15 degrees across the +/-180 seam
        assertFalse(ScanState.isHalfTurn(0.0, 90.0))      // quarter turn
    }

    @Test
    fun jsonRoundTrip() {
        val s = ScanState(2, 3)
        s.record(0.1, 0.2, g); s.record(-0.1, -0.2, g); s.skip(); s.record(0.3, 0.0, g)
        s.toggleDrain(0)
        val t = ScanState.fromJson(s.toJson())
        assertEquals(s.step, t.step)
        assertEquals(s.drains, t.drains)
        for (i in 0 until s.size) {
            assertEquals(s.riseRight[i], t.riseRight[i], 0.0)
            assertEquals(s.skipped[i], t.skipped[i])
        }
        assertEquals(s.biasX, t.biasX, 0.0)
    }
}
