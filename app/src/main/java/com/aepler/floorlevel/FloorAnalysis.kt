package com.aepler.floorlevel

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns per-square slopes into a height map, follows where water runs, and
 * explains in plain words why it does or doesn't reach the drains.
 *
 * Pure Kotlin; no Android types.
 */
class FloorAnalysis(private val scan: ScanState) {
    val rows = scan.rows
    val cols = scan.cols
    private val n = scan.size

    /** Inches above the lowest drain (NaN where unknown). */
    val height = DoubleArray(n) { Double.NaN }

    /** For each square, the square its water finally stops in (-1 if unknown). */
    val endsAt = IntArray(n) { -1 }

    /** How deep water must stand here before it can spill to a drain or out the door (inches). */
    val pondDepth = DoubleArray(n) { Double.NaN }

    /** Number of separate measured pieces of floor (skipped squares can split it). */
    var pieces = 0
        private set

    /** Which piece each square belongs to (-1 if it has no height). */
    private val pieceOf = IntArray(n) { -1 }

    data class Finding(val severity: Severity, val title: String, val detail: String)

    enum class Severity { PROBLEM, WARNING, INFO, GOOD }

    init {
        solveHeights()
        routeWater()
        floodFromDrains()
    }

    val findings: List<Finding> by lazy { buildFindings() }

    fun drainsToDrain(i: Int) = endsAt[i] >= 0 && endsAt[i] in scan.drains

    fun slopeMagnitude(i: Int) = hypot(scan.riseRight[i], scan.riseTowardDoor[i])

    // ---------------------------------------------------------------- heights

    private fun solveHeights() {
        val rr = scan.riseRight
        val rd = scan.riseTowardDoor
        val measured = BooleanArray(n) { scan.isMeasured(it) }
        val h = DoubleArray(n)
        val degree = IntArray(n)
        for (i in 0 until n) if (measured[i]) degree[i] = neighbors4(i).count { measured[it] }

        // Least-squares fit of heights to the slopes between neighbouring squares
        // (successive over-relaxation on the grid Laplacian).
        val omega = 1.8
        for (sweep in 0 until 50_000) {
            var maxChange = 0.0
            for (i in 0 until n) {
                if (!measured[i] || degree[i] == 0) continue
                val r = scan.rowOf(i)
                val c = scan.colOf(i)
                var sum = 0.0
                if (c > 0 && measured[i - 1]) sum += h[i - 1] + (rr[i - 1] + rr[i]) / 2
                if (c < cols - 1 && measured[i + 1]) sum += h[i + 1] - (rr[i] + rr[i + 1]) / 2
                if (r > 0 && measured[i - cols]) sum += h[i - cols] + (rd[i - cols] + rd[i]) / 2
                if (r < rows - 1 && measured[i + cols]) sum += h[i + cols] - (rd[i] + rd[i + cols]) / 2
                val change = omega * (sum / degree[i] - h[i])
                h[i] += change
                if (abs(change) > maxChange) maxChange = abs(change)
            }
            if (maxChange < 1e-9) break
        }

        // Each connected piece gets its own zero: its lowest drain, else its lowest square.
        val piece = pieceOf
        for (start in 0 until n) {
            if (!measured[start] || degree[start] == 0 || piece[start] >= 0) continue
            val members = mutableListOf(start)
            piece[start] = pieces
            var k = 0
            while (k < members.size) {
                for (j in neighbors4(members[k])) {
                    if (measured[j] && piece[j] < 0) { piece[j] = pieces; members.add(j) }
                }
                k++
            }
            val drainHeights = members.filter { it in scan.drains }.map { h[it] }
            val zero = drainHeights.minOrNull() ?: members.minOf { h[it] }
            for (m in members) height[m] = h[m] - zero
            pieces++
        }
    }

    // ------------------------------------------------------------ water flow

    private fun routeWater() {
        val next = IntArray(n) { -1 }
        for (i in 0 until n) {
            if (height[i].isNaN() || i in scan.drains) continue
            var best = -1
            var bestDrop = 0.0
            for (j in neighbors8(i)) {
                if (height[j].isNaN()) continue
                val drop = (height[i] - height[j]) / distance(i, j)
                if (drop > bestDrop) { bestDrop = drop; best = j }
            }
            next[i] = best
        }
        for (i in 0 until n) {
            if (height[i].isNaN()) continue
            var j = i
            while (next[j] >= 0) j = next[j]  // strictly downhill, so no loops
            endsAt[i] = j
        }
    }

    /**
     * "Priority flood": the water level each square must reach before it can spill
     * to a drain. The door row also counts as a way out: water there runs out of
     * the room rather than standing (the drainage finding reports it separately).
     */
    private fun floodFromDrains() {
        val level = DoubleArray(n) { Double.NaN }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        val doorRow = (0 until cols).map { scan.index(rows - 1, it) }
        for (o in scan.drains + doorRow) {
            if (height[o].isNaN() || !level[o].isNaN()) continue
            level[o] = height[o]
            queue.add(height[o] to o)
        }
        while (queue.isNotEmpty()) {
            val (lv, i) = queue.poll()!!
            if (lv > level[i]) continue
            for (j in neighbors8(i)) {
                if (height[j].isNaN() || !level[j].isNaN()) continue
                level[j] = maxOf(height[j], lv)
                queue.add(level[j] to j)
            }
        }
        for (i in 0 until n) if (!level[i].isNaN()) pondDepth[i] = level[i] - height[i]
    }

    // -------------------------------------------------------------- findings

    data class Puddle(val cells: List<Int>, val gallons: Double, val deepest: Int, val maxDepth: Double)

    val puddles: List<Puddle> by lazy {
        val seen = BooleanArray(n)
        val out = mutableListOf<Puddle>()
        for (s in 0 until n) {
            if (seen[s] || !(pondDepth[s] > PUDDLE_MIN_DEPTH)) continue
            val cells = mutableListOf(s)
            seen[s] = true
            var k = 0
            while (k < cells.size) {
                for (j in neighbors8(cells[k])) {
                    if (!seen[j] && pondDepth[j] > PUDDLE_MIN_DEPTH) { seen[j] = true; cells.add(j) }
                }
                k++
            }
            val deepest = cells.maxBy { pondDepth[it] }
            out.add(Puddle(cells, cells.sumOf { gallonsPerSquare(pondDepth[it]) }, deepest, pondDepth[deepest]))
        }
        out.sortedByDescending { it.gallons }
    }

    private fun buildFindings(): List<Finding> {
        val out = mutableListOf<Finding>()
        val known = (0 until n).filter { !height[it].isNaN() }
        val measuredCount = (0 until n).count { scan.isMeasured(it) }
        if (measuredCount < 4) {
            out += Finding(Severity.WARNING, "Not enough squares measured",
                "Measure at least a few squares in a row so the slopes can be joined into a map.")
            return out
        }
        val drains = scan.drains.filter { !height[it].isNaN() }
        if (scan.drains.isEmpty()) {
            out += Finding(Severity.WARNING, "No drains marked",
                "Tap the drain squares on the map, then press Diagnose again.")
        }

        if (pieces > 1) {
            out += Finding(Severity.INFO, "Floor measured in $pieces separate pieces",
                "Skipped squares cut the floor into pieces that don't touch, so heights in different " +
                    "pieces can't be compared. Each piece is shown relative to its own drain or low point.")
        }

        // 1. Drain set higher than the floor around it.
        for ((k, d) in scan.drains.withIndex()) {
            if (height[d].isNaN()) continue
            val lowest = neighbors8(d).filter { !height[it].isNaN() }.minByOrNull { height[it] } ?: continue
            val above = height[d] - height[lowest]
            if (above > DRAIN_HIGH_TOLERANCE) {
                out += Finding(Severity.PROBLEM, "Drain ${k + 1} sits high",
                    "${place(d)} is about ${inches(above)} higher than the floor right " +
                        "next to it at ${place(lowest)}. Water stops short of the grate. Usually the drain " +
                        "body or grate was set too high, or the floor near it was not sloped down to it.")
            }
        }

        // 2. Lowest point of the floor is not a drain.
        if (drains.isNotEmpty()) {
            val lowest = known.minBy { height[it] }
            if (lowest !in scan.drains && -height[lowest] > DRAIN_HIGH_TOLERANCE) {
                out += Finding(Severity.PROBLEM, "Lowest spot is not a drain",
                    "The lowest part of the floor is at ${place(lowest)}, about ${inches(-height[lowest])} " +
                        "below the lowest drain. Water collects there instead of draining.")
            }
        }

        // 3. Where does the water go?
        if (drains.isNotEmpty()) {
            val toDrain = known.count { drainsToDrain(it) }
            val pct = 100.0 * toDrain / known.size
            val elsewhere = known.filter { !drainsToDrain(it) }.groupBy { endsAt[it] }
                .entries.sortedByDescending { it.value.size }
            val perDrain = scan.drains.withIndex().joinToString("; ") { (k, d) ->
                "Drain ${k + 1}: ${known.count { endsAt[it] == d }} sq ft"
            }
            if (pct >= 95) {
                out += Finding(Severity.GOOD, "${pct.roundToInt()}% of the floor runs to a drain", perDrain)
            } else {
                val where = elsewhere.take(3).joinToString("; ") { (end, cells) ->
                    "${cells.size} sq ft runs to ${place(end)}"
                }
                out += Finding(if (pct < 80) Severity.PROBLEM else Severity.WARNING,
                    "Only ${pct.roundToInt()}% of the floor runs to a drain",
                    "$perDrain. Elsewhere: $where. Water landing there ends up against the walls or " +
                        "doorway, where the towels are.")
            }
        }

        // 4. Low spots (birdbaths) that hold water.
        val total = puddles.sumOf { it.gallons }
        if (puddles.isNotEmpty() && total >= 0.05) {
            val list = puddles.take(3).joinToString("; ") {
                "${place(it.deepest)}: up to ${inches(it.maxDepth)} deep, ${it.cells.size} sq ft, " +
                    "about ${"%.1f".format(it.gallons)} gal"
            }
            out += Finding(if (total >= 0.5) Severity.PROBLEM else Severity.WARNING,
                "Low spots hold about ${"%.1f".format(total)} gallons",
                "Before it can reach a drain or the door, water has to fill these dips: $list. They refill every " +
                    "shower. (Rough number: 1 ft squares miss small dips and humps.)")
        }

        // 5. Whole floor tilted one way.
        // Heights in different pieces have unrelated zeros, so fit only the biggest piece.
        val mainPiece = known.groupBy { pieceOf[it] }.values.maxByOrNull { it.size } ?: emptyList()
        planeFit(mainPiece)?.let { (a, b, r2) ->
            val mag = hypot(a, b)
            if (mag >= 0.08 && r2 >= 0.5) {
                val toward = direction(-a, -b)
                val plane = { i: Int -> a * scan.colOf(i) + b * scan.rowOf(i) }
                val lo = mainPiece.minOf(plane)
                val range = mainPiece.maxOf(plane) - lo
                val pieceDrains = drains.filter { it in mainPiece }
                val drainsLow = pieceDrains.isNotEmpty() && pieceDrains.all { plane(it) - lo <= 0.3 * range }
                if (!drainsLow && pieceDrains.isNotEmpty()) {
                    out += Finding(Severity.PROBLEM, "Whole floor tilts toward $toward",
                        "Overall the floor drops about ${slope(mag)} toward $toward, not toward the " +
                            "drains, so it behaves like one tilted slab. This usually means the floor " +
                            "underneath (mortar bed or subfloor) was set to the wrong slope or was meant to be level.")
                } else {
                    out += Finding(Severity.INFO, "Overall tilt: ${slope(mag)} toward $toward",
                        if (pieces > 1) "The largest measured piece of floor leans toward $toward."
                        else "The floor as a whole leans toward $toward.")
                }
            }
        }

        // 6. Squares that are flat or slope away from the nearest drain.
        val local = (0 until n).filter { scan.isMeasured(it) && it !in scan.drains }
        if (local.isNotEmpty()) {
            val flat = local.count { slopeMagnitude(it) < FLAT_SLOPE }
            if (flat * 5 >= local.size) {
                out += Finding(Severity.WARNING, "$flat of ${local.size} squares are nearly flat",
                    "They slope less than 1/8 in per ft. Water spreads into a thin film there and stalls. " +
                        "Floors that must drain are usually built to about 1/4 in per ft toward the drain.")
            }
            if (drains.isNotEmpty()) {
                val away = local.count { slopesAwayFromDrains(it) }
                if (away > 0) {
                    out += Finding(if (away * 5 >= local.size) Severity.PROBLEM else Severity.WARNING,
                        "$away squares slope away from the nearest drain",
                        "Their downhill direction points away from the closest drain (red arrows on the map).")
                }
            }
            val steep = local.count { slopeMagnitude(it) > STEEP_SLOPE }
            if (steep > 0) {
                out += Finding(Severity.INFO, "$steep squares are steeper than about 1/4 in per ft",
                    "Accessible routes and roll-in showers are commonly limited to 1:48 (1/4 in per ft). " +
                        "Worth checking if this room has to meet accessibility rules.")
            }
        }

        if (drains.isNotEmpty() && out.none { it.severity == Severity.PROBLEM || it.severity == Severity.WARNING }) {
            out += Finding(Severity.GOOD, "The floor's shape looks OK",
                "If water still ends up in towels, the floor is probably not the cause. Check the drains " +
                    "themselves (partly clogged, drain too small for the shower's flow, grate with little " +
                    "open area), and whether the shower sprays water past where the floor slopes to a drain.")
        }
        return out.sortedBy { it.severity.ordinal }
    }

    fun slopesAwayFromDrains(i: Int): Boolean {
        val m = slopeMagnitude(i)
        if (m < 0.06 || scan.drains.isEmpty()) return false
        val r = scan.rowOf(i)
        val c = scan.colOf(i)
        val d = scan.drains.minBy { hypot((scan.rowOf(it) - r).toDouble(), (scan.colOf(it) - c).toDouble()) }
        val toC = (scan.colOf(d) - c).toDouble()
        val toR = (scan.rowOf(d) - r).toDouble()
        // Downhill direction is the opposite of the rise.
        val dot = -scan.riseRight[i] * toC - scan.riseTowardDoor[i] * toR
        return dot < 0
    }

    /** Least-squares plane height = a*col + b*row + k; returns (a, b, R^2). */
    private fun planeFit(cells: List<Int>): Triple<Double, Double, Double>? {
        if (cells.size < 4) return null
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        val m = cells.size.toDouble()
        for (i in cells) {
            val x = scan.colOf(i).toDouble(); val y = scan.rowOf(i).toDouble(); val z = height[i]
            sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y
            sxz += x * z; syz += y * z; sz += z
        }
        // Centre the data so the 3x3 system becomes 2x2.
        val cxx = sxx - sx * sx / m; val cxy = sxy - sx * sy / m; val cyy = syy - sy * sy / m
        val cxz = sxz - sx * sz / m; val cyz = syz - sy * sz / m
        val det = cxx * cyy - cxy * cxy
        val a: Double
        val b: Double
        when {
            abs(det) > 1e-9 -> { a = (cxz * cyy - cyz * cxy) / det; b = (cyz * cxx - cxz * cxy) / det }
            cxx > 1e-9 -> { a = cxz / cxx; b = 0.0 }   // a single row
            cyy > 1e-9 -> { a = 0.0; b = cyz / cyy }   // a single column
            else -> return null
        }
        val k = (sz - a * sx - b * sy) / m
        val mean = sz / m
        var ssTot = 0.0; var ssRes = 0.0
        for (i in cells) {
            val z = height[i]
            val p = a * scan.colOf(i) + b * scan.rowOf(i) + k
            ssTot += (z - mean) * (z - mean); ssRes += (z - p) * (z - p)
        }
        return Triple(a, b, if (ssTot < 1e-12) 1.0 else 1 - ssRes / ssTot)
    }

    // ------------------------------------------------------------- helpers

    fun place(i: Int): String {
        val r = scan.rowOf(i)
        val c = scan.colOf(i)
        val drainNo = scan.drains.indexOf(i)
        val base = "row ${r + 1}, square ${c + 1}"
        val edges = buildList {
            if (r == 0) add("back wall")
            if (r == rows - 1) add("door side")
            if (c == 0) add("left wall")
            if (c == cols - 1) add("right wall")
        }
        return when {
            drainNo >= 0 -> "Drain ${drainNo + 1} ($base)"
            edges.isNotEmpty() -> "$base, by the ${edges.joinToString(" / ")}"
            else -> base
        }
    }

    private fun neighbors4(i: Int): List<Int> {
        val r = scan.rowOf(i); val c = scan.colOf(i)
        return buildList {
            if (c > 0) add(i - 1)
            if (c < cols - 1) add(i + 1)
            if (r > 0) add(i - cols)
            if (r < rows - 1) add(i + cols)
        }
    }

    private fun neighbors8(i: Int): List<Int> {
        val r = scan.rowOf(i); val c = scan.colOf(i)
        return buildList {
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val rr = r + dr; val cc = c + dc
                if (rr in 0 until rows && cc in 0 until cols) add(scan.index(rr, cc))
            }
        }
    }

    private fun distance(i: Int, j: Int) =
        if (scan.rowOf(i) != scan.rowOf(j) && scan.colOf(i) != scan.colOf(j)) SQRT2 else 1.0

    companion object {
        /** A drain more than ~1/16 in above its neighbour counts as high. */
        const val DRAIN_HIGH_TOLERANCE = 0.06
        const val PUDDLE_MIN_DEPTH = 1.0 / 32
        const val FLAT_SLOPE = 0.125
        const val STEEP_SLOPE = 0.3
        private val SQRT2 = sqrt(2.0)

        /** Water over one square foot, [depth] inches deep, in US gallons. */
        fun gallonsPerSquare(depth: Double) = depth * 144.0 / 231.0

        /** e.g. 0.19 -> "3/16 in (0.19 in)". */
        fun inches(x: Double): String {
            val sixteenths = (abs(x) * 16).roundToInt()
            val whole = sixteenths / 16
            var num = sixteenths % 16
            var den = 16
            while (num != 0 && num % 2 == 0) { num /= 2; den /= 2 }
            val frac = when {
                sixteenths == 0 -> "less than 1/16"
                num == 0 -> "$whole"
                whole == 0 -> "$num/$den"
                else -> "$whole $num/$den"
            }
            return "$frac in (%.2f in)".format(abs(x))
        }

        fun slope(inPerFt: Double) = "%.2f in per ft (%.1f%%)".format(inPerFt, inPerFt / 12 * 100)

        /** Name the direction of a (right, toward-door) vector. */
        fun direction(dx: Double, dy: Double): String {
            val deg = Math.toDegrees(atan2(dy, dx))
            val sector = (((deg + 360 + 22.5) % 360) / 45).toInt()
            return listOf(
                "the right wall", "the front-right corner (door side)", "the door",
                "the front-left corner (door side)", "the left wall", "the back-left corner",
                "the back wall", "the back-right corner",
            )[sector]
        }
    }
}
