package com.aepler.floorlevel

import org.json.JSONArray
import org.json.JSONObject

/**
 * One floor scan: a grid of 1 ft squares, walked row by row in a snake
 * pattern (row 1 left to right, row 2 right to left, ...).
 *
 * Grid frame, as seen standing in the doorway facing into the room:
 *  - row 0 is along the back wall, the last row is at the door;
 *  - col 0 is at the left wall.
 * The phone always lies face up with its top edge pointing at the back wall.
 *
 * Per square we store the floor slope as "rise in inches per foot":
 *  - [riseRight]: how much the floor rises moving one foot to the right;
 *  - [riseTowardDoor]: how much it rises moving one foot toward the door.
 */
class ScanState(val rows: Int, val cols: Int) {
    val size = rows * cols
    val riseRight = DoubleArray(size) { Double.NaN }
    val riseTowardDoor = DoubleArray(size) { Double.NaN }
    val skipped = BooleanArray(size)
    /** Drain squares, in the order the user tapped them (Drain 1, Drain 2, ...). */
    val drains = mutableListOf<Int>()

    /** Position in the snake walk; equals [size] when every square is done. */
    var step = 0
        private set

    // Calibration: the phone's own tilt (camera bump, bag, sensor offset), as
    // accelerometer x/z and y/z ratios. Found by measuring the first square
    // twice, the second time turned 180 degrees.
    var biasX = Double.NaN
        private set
    var biasY = Double.NaN
        private set
    var calibrationCell = -1
        private set
    private var firstX = Double.NaN
    private var firstY = Double.NaN

    val isCalibrated get() = !biasX.isNaN()
    val awaitingTurnedReading get() = !firstX.isNaN()
    val isDone get() = step >= size

    fun index(row: Int, col: Int) = row * cols + col
    fun rowOf(i: Int) = i / cols
    fun colOf(i: Int) = i % cols

    fun cellAtStep(s: Int): Int {
        val r = s / cols
        val k = s % cols
        val c = if (r % 2 == 0) k else cols - 1 - k
        return index(r, c)
    }

    val currentCell get() = if (isDone) -1 else cellAtStep(step)

    fun isMeasured(i: Int) = !riseRight[i].isNaN()

    enum class Outcome { TURN_PHONE_AROUND, RECORDED }

    /** Feed one averaged accelerometer reading for the current square. */
    fun record(ax: Double, ay: Double, az: Double): Outcome {
        check(!isDone)
        val rx = ax / az
        val ry = ay / az
        if (!isCalibrated) {
            if (!awaitingTurnedReading) {
                firstX = rx; firstY = ry
                return Outcome.TURN_PHONE_AROUND
            }
            // Reading A = bias + tilt, reading B (turned 180) = bias - tilt.
            biasX = (firstX + rx) / 2
            biasY = (firstY + ry) / 2
            calibrationCell = currentCell
            store(currentCell, firstX - biasX, firstY - biasY)
            firstX = Double.NaN; firstY = Double.NaN
        } else {
            store(currentCell, rx - biasX, ry - biasY)
        }
        step++
        return Outcome.RECORDED
    }

    /** Throw away the first half of the calibration (the turned reading was bad). */
    fun restartCalibration() {
        firstX = Double.NaN; firstY = Double.NaN
    }

    private fun store(i: Int, tiltX: Double, tiltY: Double) {
        // Phone +x points right; phone +y points at the back wall (away from the door).
        riseRight[i] = 12.0 * tiltX
        riseTowardDoor[i] = -12.0 * tiltY
        skipped[i] = false
    }

    fun skip() {
        check(!isDone)
        firstX = Double.NaN; firstY = Double.NaN
        skipped[currentCell] = true
        step++
    }

    /** Undo the last square (or the half-done calibration reading). */
    fun redoLast(): Boolean {
        if (awaitingTurnedReading) {
            restartCalibration()
            return true
        }
        if (step == 0) return false
        step--
        val i = cellAtStep(step)
        riseRight[i] = Double.NaN
        riseTowardDoor[i] = Double.NaN
        skipped[i] = false
        if (i == calibrationCell) {
            biasX = Double.NaN; biasY = Double.NaN; calibrationCell = -1
        }
        return true
    }

    /** Stop early: everything not yet visited is left unmeasured. */
    fun finishEarly() {
        firstX = Double.NaN; firstY = Double.NaN
        step = size
    }

    fun toggleDrain(i: Int) {
        if (!drains.remove(i)) drains.add(i)
    }

    fun toJson(): String = JSONObject().apply {
        put("rows", rows); put("cols", cols); put("step", step)
        put("riseRight", numbers(riseRight)); put("riseTowardDoor", numbers(riseTowardDoor))
        put("skipped", JSONArray().apply { skipped.forEach { put(it) } })
        put("drains", JSONArray(drains))
        put("biasX", nullable(biasX)); put("biasY", nullable(biasY))
        put("calibrationCell", calibrationCell)
    }.toString()

    companion object {
        private fun numbers(a: DoubleArray) = JSONArray().apply { a.forEach { put(nullable(it)) } }
        private fun nullable(d: Double): Any = if (d.isNaN()) JSONObject.NULL else d
        private fun JSONObject.dbl(k: String) = if (isNull(k)) Double.NaN else getDouble(k)
        private fun JSONArray.dbl(i: Int) = if (isNull(i)) Double.NaN else getDouble(i)

        fun fromJson(s: String): ScanState {
            val o = JSONObject(s)
            val st = ScanState(o.getInt("rows"), o.getInt("cols"))
            val rr = o.getJSONArray("riseRight")
            val rd = o.getJSONArray("riseTowardDoor")
            val sk = o.getJSONArray("skipped")
            for (i in 0 until st.size) {
                st.riseRight[i] = rr.dbl(i)
                st.riseTowardDoor[i] = rd.dbl(i)
                st.skipped[i] = sk.getBoolean(i)
            }
            val dr = o.getJSONArray("drains")
            for (i in 0 until dr.length()) st.drains.add(dr.getInt(i))
            st.step = o.getInt("step")
            st.biasX = o.dbl("biasX"); st.biasY = o.dbl("biasY")
            st.calibrationCell = o.getInt("calibrationCell")
            return st
        }
    }
}
