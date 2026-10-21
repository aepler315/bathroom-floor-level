package com.aepler.floorlevel

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Watches raw accelerometer samples and works out when the phone has been
 * picked up, set down flat, and left still long enough to measure.
 *
 * Cycle: WAITING_FOR_LIFT -> MOVING -> MEASURING -> (Measured) -> WAITING_FOR_LIFT.
 *
 * Pure Kotlin (no Android types) so it can be unit tested with synthetic data.
 */
class PlacementDetector(
    /** How long the phone must already be still before measuring starts. */
    private val settleMs: Long = 800,
    /** How long readings are averaged once it is still. */
    private val measureMs: Long = 1500,
    /** Per-axis standard deviation (m/s^2) below which the phone counts as still. */
    private val stillStd: Double = 0.06,
    /** Low-pass deviation from the resting reading (m/s^2) that counts as "picked up". */
    private val liftThreshold: Double = 1.0,
    /** Single-sample jump (m/s^2) during measuring that throws the measurement away. */
    private val disturbThreshold: Double = 0.3,
) {
    enum class Phase { WAITING_FOR_LIFT, MOVING, MEASURING }

    sealed interface Event {
        data object Lifted : Event
        data object MeasuringStarted : Event
        data object Disturbed : Event
        data class Measured(val x: Double, val y: Double, val z: Double, val samples: Int) : Event
    }

    private data class Sample(val t: Long, val x: Double, val y: Double, val z: Double)

    var phase: Phase = Phase.MOVING
        private set

    private val window = ArrayDeque<Sample>()
    private var lpX = 0.0
    private var lpY = 0.0
    private var lpZ = 0.0
    private var lpReady = false
    private var refX = 0.0
    private var refY = 0.0
    private var refZ = 0.0
    private var refReady = false

    private var measureStart = 0L
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var count = 0
    private var settleX = 0.0
    private var settleY = 0.0
    private var settleZ = 0.0

    /** Start looking for the phone to be set down (e.g. right after Start is pressed). */
    fun expectPlacement() {
        phase = Phase.MOVING
        window.clear()
    }

    /**
     * Require the phone to be picked up before the next measurement. Used after
     * Skip/Redo, where the phone may still be lying on the old square.
     * The resting reference is taken from the next samples.
     */
    fun expectLift() {
        phase = Phase.WAITING_FOR_LIFT
        refReady = false
        window.clear()
    }

    fun onSample(tMs: Long, x: Double, y: Double, z: Double): Event? {
        if (!lpReady) {
            lpX = x; lpY = y; lpZ = z; lpReady = true
        } else {
            // ~100 ms time constant at 50 Hz: ignores screen taps, follows real lifts.
            val a = 0.15
            lpX += a * (x - lpX); lpY += a * (y - lpY); lpZ += a * (z - lpZ)
        }

        return when (phase) {
            Phase.WAITING_FOR_LIFT -> {
                if (!refReady) {
                    refX = lpX; refY = lpY; refZ = lpZ; refReady = true
                    null
                } else if (dist(lpX - refX, lpY - refY, lpZ - refZ) > liftThreshold || !isFlat(lpX, lpY, lpZ)) {
                    phase = Phase.MOVING
                    window.clear()
                    Event.Lifted
                } else {
                    null
                }
            }

            Phase.MOVING -> {
                window.addLast(Sample(tMs, x, y, z))
                while (window.isNotEmpty() && tMs - window.first().t > settleMs) window.removeFirst()
                if (window.size >= 5 && tMs - window.first().t >= settleMs * 0.9 && windowIsStillAndFlat()) {
                    phase = Phase.MEASURING
                    measureStart = tMs
                    settleX = window.sumOf { it.x } / window.size
                    settleY = window.sumOf { it.y } / window.size
                    settleZ = window.sumOf { it.z } / window.size
                    sumX = 0.0; sumY = 0.0; sumZ = 0.0; count = 0
                    Event.MeasuringStarted
                } else {
                    null
                }
            }

            Phase.MEASURING -> {
                if (dist(x - settleX, y - settleY, z - settleZ) > disturbThreshold) {
                    phase = Phase.MOVING
                    window.clear()
                    Event.Disturbed
                } else {
                    sumX += x; sumY += y; sumZ += z; count++
                    if (tMs - measureStart >= measureMs && count > 0) {
                        val m = Event.Measured(sumX / count, sumY / count, sumZ / count, count)
                        phase = Phase.WAITING_FOR_LIFT
                        refX = m.x; refY = m.y; refZ = m.z; refReady = true
                        lpX = m.x; lpY = m.y; lpZ = m.z
                        window.clear()
                        m
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun windowIsStillAndFlat(): Boolean {
        val n = window.size
        val mx = window.sumOf { it.x } / n
        val my = window.sumOf { it.y } / n
        val mz = window.sumOf { it.z } / n
        if (!isFlat(mx, my, mz)) return false
        if (abs(dist(mx, my, mz) - GRAVITY) > 0.8) return false
        val sx = sqrt(window.sumOf { (it.x - mx) * (it.x - mx) } / n)
        val sy = sqrt(window.sumOf { (it.y - my) * (it.y - my) } / n)
        val sz = sqrt(window.sumOf { (it.z - mz) * (it.z - mz) } / n)
        return sx < stillStd && sy < stillStd && sz < stillStd
    }

    companion object {
        const val GRAVITY = 9.80665

        /** Face up and within ~15 degrees of level. */
        fun isFlat(x: Double, y: Double, z: Double): Boolean {
            val g = dist(x, y, z)
            return g > 1e-6 && z / g > 0.966
        }

        private fun dist(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
    }
}
