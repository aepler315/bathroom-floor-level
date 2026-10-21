package com.aepler.floorlevel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.ceil

class MainActivity : Activity(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private var accel: Sensor? = null
    private var rotation: Sensor? = null
    private val detector = PlacementDetector()
    private var tones: ToneGenerator? = null

    private var scan: ScanState? = null
    private var screen = Screen.SETUP
    private enum class Screen { SETUP, SCAN, RESULTS }

    // Heading (from the gyro-based game rotation vector) is only used to check
    // that the phone really was turned around for calibration.
    private var yawNow = Double.NaN
    private var yawAtFirstReading = Double.NaN

    private val dp get() = resources.displayMetrics.density
    private val prefs get() = getSharedPreferences("scan", Context.MODE_PRIVATE)

    // Scan screen views.
    private var instruction: TextView? = null
    private var status: TextView? = null
    private var map: FloorMapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        showSetup()
    }

    override fun onResume() {
        super.onResume()
        accel?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        save()
    }

    override fun onDestroy() {
        tones?.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.SETUP -> super.onBackPressed()
            Screen.SCAN -> confirm("Stop scanning?", "Your squares so far are kept. You can resume from the start screen.") { showSetup() }
            Screen.RESULTS -> showSetup()
        }
    }

    // ------------------------------------------------------------------ setup

    private fun showSetup() {
        screen = Screen.SETUP
        clearScanViews()
        val root = column()
        root.addView(title("Floor Level"))
        root.addView(body(
            "Maps the slope of the floor one square foot at a time and works out where the water goes.\n\n" +
                "1. Stand in the doorway facing in. Row 1 is along the back wall; square 1 is at the left wall.\n" +
                "2. Lay the phone face up in the middle of each square, top edge pointing at the back wall, every time.\n" +
                "3. Let go. It beeps when it has the reading. Pick it up, go to the next square.\n" +
                "4. Rows snake back and forth; the yellow square on the map shows where to go next. " +
                "Press Skip for squares you can't reach (toilet, cabinet).\n\n" +
                "Enter the floor size, rounded up to whole feet:"))

        val sizes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(body("Width = left wall to right wall.  Length = back wall to door."))
        val width = numberField("Width ft", prefs.getString("lastWidth", ""))
        val length = numberField("Length ft", prefs.getString("lastLength", ""))
        sizes.addView(width, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sizes.addView(TextView(this).apply { text = " × "; textSize = 22f; setTextColor(Color.WHITE) })
        sizes.addView(length, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(sizes)

        val start = bigButton("START", Color.rgb(30, 160, 70), 160)
        start.setOnClickListener {
            val w = width.text.toString().toDoubleOrNull()
            val l = length.text.toString().toDoubleOrNull()
            if (w == null || l == null || w < 1 || l < 1 || w > 40 || l > 40) {
                Toast.makeText(this, "Enter width and length in feet (1 to 40).", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit().putString("lastWidth", width.text.toString()).putString("lastLength", length.text.toString()).apply()
            scan = ScanState(ceil(l).toInt(), ceil(w).toInt())
            save()
            showScan()
        }
        root.addView(start)

        val saved = load()
        if (saved != null) {
            val measured = (0 until saved.size).count { saved.isMeasured(it) }
            val resume = bigButton(
                if (saved.isDone) "Open last results" else "Resume last scan ($measured squares done)",
                Color.rgb(60, 90, 160), 64)
            resume.setOnClickListener {
                scan = saved
                if (saved.isDone) showResults() else showScan()
            }
            root.addView(resume)
        }
        setContentView(scroll(root))
    }

    // ------------------------------------------------------------------- scan

    private fun showScan() {
        screen = Screen.SCAN
        val s = scan ?: return
        val root = column()
        instruction = TextView(this).apply {
            textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 20f; setTextColor(Color.rgb(255, 214, 0)); gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        map = FloorMapView(this).apply { scan = s }
        root.addView(instruction)
        root.addView(status)
        root.addView(map)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val skip = smallButton("Skip\nsquare")
        val redo = smallButton("Redo\nlast")
        val done = smallButton("Finish\nnow")
        skip.setOnClickListener {
            s.skip(); afterStep(lift = true)
        }
        redo.setOnClickListener {
            if (s.redoLast()) afterStep(lift = true)
        }
        done.setOnClickListener {
            confirm("Finish now?", "Squares you haven't reached will be left blank.") { s.finishEarly(); save(); showResults() }
        }
        for (b in listOf(skip, redo, done)) row.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(4, 4, 4, 4) })
        root.addView(row)
        setContentView(scroll(root))

        if (accel == null) {
            instruction?.text = "This phone has no accelerometer."
            return
        }
        // Pressing Start means the phone is in hand: just wait for it to be set down.
        detector.expectPlacement()
        refreshScan("Set the phone down on the yellow square.")
    }

    private fun afterStep(lift: Boolean) {
        save()
        if (scan?.isDone == true) { finishTone(); showResults(); return }
        // Always wait for a lift: the phone may still be lying on the previous square.
        detector.expectLift()
        refreshScan(if (lift) "Pick up the phone and set it on the yellow square." else "Got it. Pick up the phone.")
    }

    private fun refreshScan(statusText: String) {
        val s = scan ?: return
        val i = s.currentCell
        if (i >= 0) {
            val r = s.rowOf(i) + 1
            val c = s.colOf(i) + 1
            instruction?.text = if (s.awaitingTurnedReading) {
                "Same square, turned around:\nset it down with the top pointing at YOU"
            } else {
                val dir = if ((r - 1) % 2 == 0) "→" else "←"
                "Row $r of ${s.rows}  $dir  square $c of ${s.cols}"
            }
        }
        status?.text = statusText
        map?.invalidate()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val m = FloatArray(9)
            val o = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(m, event.values)
            SensorManager.getOrientation(m, o)
            yawNow = Math.toDegrees(o[0].toDouble())
            return
        }
        if (screen != Screen.SCAN) return
        val s = scan ?: return
        if (s.isDone) return
        val v = event.values
        when (val e = detector.onSample(event.timestamp / 1_000_000, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())) {
            PlacementDetector.Event.Lifted -> refreshScan("Set the phone down on the yellow square.")
            PlacementDetector.Event.MeasuringStarted -> refreshScan("Measuring… don't touch")
            PlacementDetector.Event.Disturbed -> refreshScan("Moved — waiting for it to sit still")
            is PlacementDetector.Event.Measured -> onMeasured(s, e)
            null -> {}
        }
    }

    private fun onMeasured(s: ScanState, e: PlacementDetector.Event.Measured) {
        val wasTurnedReading = s.awaitingTurnedReading
        if (wasTurnedReading && !yawNow.isNaN() && !yawAtFirstReading.isNaN()) {
            val turn = abs(((yawNow - yawAtFirstReading + 540) % 360) - 180)  // 0 = no turn, 180 = half turn
            if (turn > 45) {
                errorTone()
                refreshScan("That wasn't a half turn. Pick it up and set it down with the top pointing at you.")
                detector.expectLift()
                return
            }
        }
        when (s.record(e.x, e.y, e.z)) {
            ScanState.Outcome.TURN_PHONE_AROUND -> {
                yawAtFirstReading = yawNow
                okTone()
                save()
                detector.expectLift()
                refreshScan("First reading done. Now pick it up and turn it around.")
            }
            ScanState.Outcome.RECORDED -> {
                okTone()
                if (wasTurnedReading) {
                    // Turn back to the normal direction for the rest of the scan.
                    save()
                    if (s.isDone) { finishTone(); showResults(); return }
                    detector.expectLift()
                    refreshScan("Calibrated. Point the top at the back wall again from now on.")
                } else {
                    afterStep(lift = false)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- results

    private fun showResults() {
        screen = Screen.RESULTS
        clearScanViews()
        val s = scan ?: return
        val root = column()
        root.addView(title("Results"))
        val hint = body("")
        val view = FloorMapView(this)
        view.scan = s
        val findingsBox = column().apply { setPadding(0, 0, 0, 0) }

        fun refresh() {
            val a = FloorAnalysis(s)
            view.analysis = a
            hint.text = if (s.drains.isEmpty()) {
                "Tap each drain square on the map (the round drain at the shower and the square one in the room)."
            } else {
                "Drains marked: ${s.drains.size}. Tap a square to add or remove a drain."
            }
            findingsBox.removeAllViews()
            for (f in a.findings) findingsBox.addView(findingCard(f))
            findingsBox.addView(body(LEGEND))
        }

        view.onCellTap = { i ->
            if (s.isMeasured(i)) {
                s.toggleDrain(i); save(); refresh()
            } else {
                Toast.makeText(this, "That square wasn't measured.", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(hint)
        root.addView(view)
        root.addView(findingsBox)

        val share = bigButton("Share report", Color.rgb(60, 90, 160), 64)
        share.setOnClickListener { shareReport(s) }
        val again = bigButton("New scan", Color.rgb(70, 70, 70), 64)
        again.setOnClickListener {
            confirm("Start a new scan?", "This result will be replaced. Share it first if you need it.") {
                prefs.edit().remove("state").apply(); scan = null; showSetup()
            }
        }
        root.addView(share)
        root.addView(again)
        refresh()
        setContentView(scroll(root))
    }

    private fun shareReport(s: ScanState) {
        val a = FloorAnalysis(s)
        val sb = StringBuilder()
        sb.appendLine("Bathroom floor slope report (${s.cols} ft wide × ${s.rows} ft long)")
        sb.appendLine("Rows run from the back wall (row 1) to the door; square 1 is at the left wall.")
        sb.appendLine()
        for (f in a.findings) {
            sb.appendLine("[${f.severity}] ${f.title}")
            sb.appendLine(f.detail)
            sb.appendLine()
        }
        sb.appendLine("Height above the lowest drain, inches (blank = not measured, X = skipped, D = drain):")
        for (r in 0 until s.rows) {
            sb.appendLine((0 until s.cols).joinToString("\t") { c ->
                val i = s.index(r, c)
                val d = s.drains.indexOf(i)
                when {
                    s.skipped[i] -> "X"
                    a.height[i].isNaN() -> ""
                    d >= 0 -> "D${d + 1} %+.2f".format(a.height[i])
                    else -> "%+.2f".format(a.height[i])
                }
            })
        }
        sb.appendLine()
        sb.appendLine("row,square,rise_right_in_per_ft,rise_toward_door_in_per_ft,height_in,pond_depth_in,reaches_drain")
        for (i in 0 until s.size) {
            if (!s.isMeasured(i)) continue
            sb.appendLine("%d,%d,%.3f,%.3f,%.3f,%.3f,%s".format(
                s.rowOf(i) + 1, s.colOf(i) + 1, s.riseRight[i], s.riseTowardDoor[i],
                a.height[i], if (a.pondDepth[i].isNaN()) 0.0 else a.pondDepth[i], a.drainsToDrain(i)))
        }
        sb.appendLine()
        sb.appendLine("Measured with a phone accelerometer, calibrated by a 180° turn on the first square. " +
            "Expect roughly ±0.05 in per ft per square; heights are added up from slopes, so treat small " +
            "differences (under 1/16 in) as noise.")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Bathroom floor slope report")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(send, "Share report"))
    }

    // -------------------------------------------------------------- storage

    private fun save() {
        scan?.let { prefs.edit().putString("state", it.toJson()).apply() }
    }

    private fun load(): ScanState? =
        prefs.getString("state", null)?.let { runCatching { ScanState.fromJson(it) }.getOrNull() }

    // ------------------------------------------------------------- feedback

    private fun okTone() {
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        buzz(80)
    }

    private fun errorTone() {
        tones?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        buzz(300)
    }

    private fun finishTone() {
        tones?.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 600)
    }

    private fun buzz(ms: Long) {
        @Suppress("DEPRECATION")
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    // ---------------------------------------------------------- view helpers

    private fun clearScanViews() {
        instruction = null; status = null; map = null
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt()
        setPadding(p, p, p, p)
    }

    private fun scroll(v: View) = ScrollView(this).apply {
        setBackgroundColor(Color.rgb(18, 18, 18))
        addView(v, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 30f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        setPadding(0, 0, 0, (8 * dp).toInt())
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.rgb(220, 220, 220))
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun numberField(hintText: String, value: String?) = EditText(this).apply {
        hint = hintText; textSize = 20f; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(value ?: "")
    }

    private fun bigButton(label: String, color: Int, heightDp: Int) = Button(this).apply {
        text = label
        textSize = if (heightDp >= 120) 44f else 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply { setColor(color); cornerRadius = 20 * dp }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, (heightDp * dp).toInt()).apply {
            topMargin = (16 * dp).toInt()
        }
    }

    private fun smallButton(label: String) = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(70, 70, 70)); cornerRadius = 12 * dp }
        minHeight = (64 * dp).toInt()
    }

    private fun findingCard(f: FloorAnalysis.Finding) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (12 * dp).toInt()
        setPadding(p, p, p, p)
        val color = when (f.severity) {
            FloorAnalysis.Severity.PROBLEM -> Color.rgb(120, 25, 25)
            FloorAnalysis.Severity.WARNING -> Color.rgb(110, 80, 10)
            FloorAnalysis.Severity.INFO -> Color.rgb(40, 55, 80)
            FloorAnalysis.Severity.GOOD -> Color.rgb(25, 90, 45)
        }
        background = GradientDrawable().apply { setColor(color); cornerRadius = 12 * dp }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * dp).toInt() }
        addView(TextView(context).apply {
            text = f.title; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        addView(TextView(context).apply { text = f.detail; textSize = 15f; setTextColor(Color.rgb(235, 235, 235)) })
    }

    private fun confirm(t: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(t).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val LEGEND =
            "Map key: arrows point downhill (longer = steeper; red = away from the nearest drain). " +
                "Colour is height: blue below the lowest drain, white level with it, orange above. " +
                "Blue circles = dips where water stands. Red outline = water from this square never " +
                "reaches a drain. Numbers = inches above the lowest drain."
    }
}
