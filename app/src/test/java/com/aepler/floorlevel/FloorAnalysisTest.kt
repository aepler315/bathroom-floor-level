package com.aepler.floorlevel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class FloorAnalysisTest {
    /** Build a scan whose slopes come from a known height function h(row, col) in inches. */
    private fun scanOf(rows: Int, cols: Int, drains: List<Pair<Int, Int>>, h: (Double, Double) -> Double): ScanState {
        val s = ScanState(rows, cols)
        val e = 1e-4
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = s.index(r, c)
            s.riseRight[i] = (h(r.toDouble(), c + e) - h(r.toDouble(), c - e)) / (2 * e)
            s.riseTowardDoor[i] = (h(r + e, c.toDouble()) - h(r - e, c.toDouble())) / (2 * e)
        }
        s.finishEarly()
        for ((r, c) in drains) s.toggleDrain(s.index(r, c))
        return s
    }

    private fun titles(a: FloorAnalysis) = a.findings.map { it.title }

    @Test
    fun planeHeightsAreExact() {
        val s = scanOf(4, 5, listOf(0 to 0)) { r, c -> 0.1 * c + 0.25 * r }
        val a = FloorAnalysis(s)
        assertEquals(0.0, a.height[s.index(0, 0)], 1e-6)
        assertEquals(0.4 + 0.75, a.height[s.index(3, 4)], 1e-6)
    }

    @Test
    fun goodFloorSlopesToBothDrains() {
        // Two cones, 1/4 in per ft, around a shower drain and a room drain.
        val drains = listOf(1 to 1, 4 to 4)
        val s = scanOf(6, 6, drains) { r, c ->
            0.25 * minOf(hypot(r - 1, c - 1), hypot(r - 4, c - 4))
        }
        val a = FloorAnalysis(s)
        assertTrue(titles(a).toString(), a.findings.none { it.severity == FloorAnalysis.Severity.PROBLEM })
        assertTrue((0 until s.size).all { a.drainsToDrain(it) })
        assertTrue(titles(a).any { it.startsWith("100% of the floor") })
    }

    @Test
    fun floorTiltedTowardDoorIsDiagnosed() {
        // Floor falls 1/4 in per ft toward the door; drains are mid-room.
        val s = scanOf(6, 5, listOf(2 to 2, 1 to 0)) { r, _ -> -0.25 * r }
        val a = FloorAnalysis(s)
        val t = titles(a)
        assertTrue(t.toString(), t.contains("Whole floor tilts toward the door"))
        assertTrue(t.toString(), t.contains("Lowest spot is not a drain"))
        assertTrue(t.toString(), t.any { it.startsWith("Only") })
        assertFalse(a.drainsToDrain(s.index(5, 2)))
        // Water running out the door is not a "dip that holds water".
        assertTrue(t.toString(), t.none { it.startsWith("Low spots hold") })
    }

    @Test
    fun tiltIsFittedPerPiece() {
        // Floor falls 1/4 in per ft toward the right wall; a skipped column splits it in two.
        val s = scanOf(3, 5, listOf(0 to 0)) { _, c -> -0.25 * c }
        for (r in 0 until 3) {
            val i = s.index(r, 2)
            s.riseRight[i] = Double.NaN; s.riseTowardDoor[i] = Double.NaN; s.skipped[i] = true
        }
        val a = FloorAnalysis(s)
        assertEquals(2, a.pieces)
        assertTrue(titles(a).toString(), titles(a).any { it.contains("toward the right wall") })
    }

    @Test
    fun highDrainIsDiagnosed() {
        // Bowl centred one square right of the marked drain.
        val s = scanOf(5, 5, listOf(2 to 2)) { r, c -> 0.25 * hypot(r - 2, c - 3) }
        val a = FloorAnalysis(s)
        assertTrue(titles(a).toString(), titles(a).contains("Drain 1 sits high"))
    }

    @Test
    fun dipHoldsWater() {
        // Slopes to a drain in the back-left corner, with a dip in the middle.
        val s = scanOf(7, 7, listOf(0 to 0)) { r, c ->
            0.2 * hypot(r, c) - 1.0 * Math.exp(-(hypot(r - 4, c - 4) / 1.2).let { it * it })
        }
        val a = FloorAnalysis(s)
        assertTrue(a.puddles.isNotEmpty())
        assertTrue(a.pondDepth[s.index(4, 4)] > 0.1)
        assertTrue(titles(a).toString(), titles(a).any { it.startsWith("Low spots hold") })
    }

    @Test
    fun skippedSquaresAreIgnored() {
        val s = ScanState(3, 3)
        repeat(9) { i ->
            if (i == 4) s.skip() else s.record(0.0, 0.0, 9.8).let { if (it == ScanState.Outcome.TURN_PHONE_AROUND) s.record(0.0, 0.0, 9.8) }
        }
        s.toggleDrain(0)
        val a = FloorAnalysis(s)
        assertTrue(a.height[4].isNaN())
        assertEquals(1, a.pieces)
    }

    @Test
    fun formatting() {
        assertEquals("3/16 in (0.19 in)", FloorAnalysis.inches(0.19))
        assertEquals("1 1/4 in (1.25 in)", FloorAnalysis.inches(1.25))
        assertEquals("less than 1/16 in (0.02 in)", FloorAnalysis.inches(0.02))
        assertEquals("the door", FloorAnalysis.direction(0.0, 1.0))
        assertEquals("the back-left corner", FloorAnalysis.direction(-1.0, -1.0))
    }
}
