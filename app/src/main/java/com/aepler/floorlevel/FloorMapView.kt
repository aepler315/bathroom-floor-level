package com.aepler.floorlevel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Draws the floor grid: back wall at the top of the screen, door at the bottom. */
class FloorMapView(context: Context) : View(context) {
    var scan: ScanState? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var analysis: FloorAnalysis? = null
        set(v) { field = v; invalidate() }
    var onCellTap: ((Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val labelBand = 22 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    private var cell = 0f
    private var left = 0f
    private var top = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val s = scan
        val maxH = resources.displayMetrics.heightPixels * 0.55f
        val h = if (s == null) 0 else {
            val c = min(w.toFloat() / s.cols, (maxH - 2 * labelBand) / s.rows)
            (c * s.rows + 2 * labelBand).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val s = scan ?: return
        val a = analysis
        cell = min(width.toFloat() / s.cols, (height - 2 * labelBand) / s.rows)
        left = (width - cell * s.cols) / 2
        top = labelBand

        text.color = Color.LTGRAY
        text.textSize = 13 * dp
        canvas.drawText("BACK WALL", width / 2f, labelBand * 0.7f, text)
        canvas.drawText("DOOR", width / 2f, top + cell * s.rows + labelBand * 0.75f, text)

        val maxAbs = a?.let { an -> an.height.filter { !it.isNaN() }.maxOfOrNull { abs(it) } }?.coerceAtLeast(0.05) ?: 1.0

        for (i in 0 until s.size) {
            val x0 = left + s.colOf(i) * cell
            val y0 = top + s.rowOf(i) * cell
            val cx = x0 + cell / 2
            val cy = y0 + cell / 2

            fill.color = when {
                s.skipped[i] -> Color.rgb(40, 40, 40)
                a != null && !a.height[i].isNaN() -> heightColor(a.height[i] / maxAbs)
                s.isMeasured(i) -> Color.rgb(90, 110, 125)
                else -> Color.rgb(60, 60, 60)
            }
            canvas.drawRect(x0, y0, x0 + cell, y0 + cell, fill)

            if (a != null && a.pondDepth[i] > FloorAnalysis.PUDDLE_MIN_DEPTH) {
                fill.color = Color.argb(150, 40, 140, 255)
                canvas.drawCircle(cx, cy, cell * 0.38f, fill)
            }

            if (s.isMeasured(i) && i !in s.drains) {
                val away = a?.slopesAwayFromDrains(i) == true
                drawArrow(canvas, cx, cy, -s.riseRight[i], -s.riseTowardDoor[i], if (away) Color.rgb(230, 30, 30) else Color.BLACK)
            }

            if (a != null && !a.height[i].isNaN() && cell > 44 * dp) {
                text.color = Color.BLACK
                text.textSize = 10 * dp
                canvas.drawText("%+.2f".format(a.height[i]), cx, y0 + cell - 4 * dp, text)
            }

            if (s.skipped[i]) {
                line.color = Color.GRAY; line.strokeWidth = 2 * dp
                canvas.drawLine(x0 + 6 * dp, y0 + 6 * dp, x0 + cell - 6 * dp, y0 + cell - 6 * dp, line)
                canvas.drawLine(x0 + cell - 6 * dp, y0 + 6 * dp, x0 + 6 * dp, y0 + cell - 6 * dp, line)
            }

            // Water from here never reaches a drain.
            if (a != null && s.drains.isNotEmpty() && !a.height[i].isNaN() && !a.drainsToDrain(i)) {
                line.color = Color.rgb(200, 30, 30); line.strokeWidth = 1.5f * dp
                canvas.drawRect(x0 + 3 * dp, y0 + 3 * dp, x0 + cell - 3 * dp, y0 + cell - 3 * dp, line)
            }

            val drainNo = s.drains.indexOf(i)
            if (drainNo >= 0) {
                fill.color = Color.BLACK
                canvas.drawCircle(cx, cy, cell * 0.3f, fill)
                text.color = Color.WHITE
                text.textSize = cell * 0.3f
                canvas.drawText("D${drainNo + 1}", cx, cy + text.textSize * 0.35f, text)
            }

            line.color = Color.rgb(20, 20, 20); line.strokeWidth = 1 * dp
            canvas.drawRect(x0, y0, x0 + cell, y0 + cell, line)
        }

        // Where the phone goes next.
        val target = s.currentCell
        if (a == null && target >= 0) {
            val x0 = left + s.colOf(target) * cell
            val y0 = top + s.rowOf(target) * cell
            fill.color = Color.rgb(255, 214, 0)
            canvas.drawRect(x0, y0, x0 + cell, y0 + cell, fill)
            text.color = Color.BLACK
            text.textSize = min(cell * 0.28f, 18 * dp)
            canvas.drawText(if (s.awaitingTurnedReading) "TURN" else "HERE", x0 + cell / 2, y0 + cell / 2 + text.textSize * 0.35f, text)
        }
    }

    /** Arrow pointing downhill; longer = steeper (full length at 1/2 in per ft). */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, dx: Double, dy: Double, color: Int) {
        val mag = hypot(dx, dy)
        line.color = color
        fill.color = color
        if (mag < 0.03) {
            canvas.drawCircle(cx, cy, 2.5f * dp, fill)
            return
        }
        val len = cell * 0.42f * min(1.0, 0.25 + mag / 0.5 * 0.75).toFloat()
        val ang = atan2(dy, dx)
        val ex = cx + (cos(ang) * len / 2).toFloat()
        val ey = cy + (sin(ang) * len / 2).toFloat()
        val sx = cx - (cos(ang) * len / 2).toFloat()
        val sy = cy - (sin(ang) * len / 2).toFloat()
        line.strokeWidth = 2.5f * dp
        canvas.drawLine(sx, sy, ex, ey, line)
        val head = min(cell * 0.16f, 12 * dp)
        path.reset()
        path.moveTo(ex, ey)
        path.lineTo(ex - (head * cos(ang - 0.5)).toFloat(), ey - (head * sin(ang - 0.5)).toFloat())
        path.lineTo(ex - (head * cos(ang + 0.5)).toFloat(), ey - (head * sin(ang + 0.5)).toFloat())
        path.close()
        canvas.drawPath(path, fill)
    }

    /** -1 (lowest, blue) .. 0 (drain level, white) .. +1 (highest, orange). */
    private fun heightColor(t: Double): Int {
        val u = t.coerceIn(-1.0, 1.0)
        val (r, g, b) = if (u < 0) Triple(70, 130, 230) else Triple(240, 140, 40)
        val k = abs(u)
        return Color.rgb((255 + (r - 255) * k).toInt(), (255 + (g - 255) * k).toInt(), (255 + (b - 255) * k).toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = scan ?: return false
        if (onCellTap == null) return false
        if (event.action == MotionEvent.ACTION_UP && cell > 0) {
            val c = ((event.x - left) / cell).toInt()
            val r = ((event.y - top) / cell).toInt()
            if (event.x >= left && event.y >= top && c in 0 until s.cols && r in 0 until s.rows) {
                onCellTap?.invoke(s.index(r, c))
            }
        }
        return true
    }
}
