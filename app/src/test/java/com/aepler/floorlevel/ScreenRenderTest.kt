package com.aepler.floorlevel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/**
 * Smoke test that drives the three screens and saves what they look like to
 * app/build/screens/ for a human to check. Not a pixel comparison.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenRenderTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun snapshot(activity: MainActivity, name: String) {
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val content = (root as? ScrollView)?.getChildAt(0) ?: root
        val w = 1233
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, w, content.measuredHeight)
        val bmp = Bitmap.createBitmap(w, content.measuredHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(18, 18, 18))
        content.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T : View> all(v: View, type: Class<T>): List<T> = buildList {
        if (type.isInstance(v)) add(type.cast(v)!!)
        if (v is ViewGroup) for (i in 0 until v.childCount) addAll(all(v.getChildAt(i), type))
    }

    @Test
    fun setupAndScanScreens() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        snapshot(activity, "1-setup")
        val fields = all(activity.window.decorView, EditText::class.java)
        fields[0].setText("6"); fields[1].setText("8")
        all(activity.window.decorView, Button::class.java).first { it.text == "START" }.performClick()
        snapshot(activity, "2-scan")
    }

    @Test
    fun resultsScreen() {
        // A 6 x 8 floor that tilts toward the door, with a dip, and the two drains.
        val s = ScanState(8, 6)
        val h = { r: Double, c: Double -> -0.15 * r + 0.04 * c - 0.5 * Math.exp(-(hypot(r - 5, c - 2) / 1.3).let { it * it }) }
        val e = 1e-4
        for (r in 0 until 8) for (c in 0 until 6) {
            val i = s.index(r, c)
            s.riseRight[i] = (h(r.toDouble(), c + e) - h(r.toDouble(), c - e)) / (2 * e)
            s.riseTowardDoor[i] = (h(r + e, c.toDouble()) - h(r - e, c.toDouble())) / (2 * e)
        }
        s.skipped[s.index(0, 5)] = true; s.riseRight[s.index(0, 5)] = Double.NaN; s.riseTowardDoor[s.index(0, 5)] = Double.NaN
        s.finishEarly()
        s.toggleDrain(s.index(1, 1)); s.toggleDrain(s.index(4, 3))
        RuntimeEnvironment.getApplication().getSharedPreferences("scan", Context.MODE_PRIVATE)
            .edit().putString("state", s.toJson()).commit()

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val open = all(activity.window.decorView, Button::class.java).firstOrNull { it.text == "Open last results" }
        assertNotNull(open)
        open!!.performClick()
        snapshot(activity, "3-results")
    }
}
