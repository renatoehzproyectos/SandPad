package com.sandpad.pad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlin.math.abs

class TouchpadService : AccessibilityService(), PadView.Host {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var cursorView: CursorView
    private lateinit var pad: PadView
    private lateinit var bubble: TextView
    private lateinit var padLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var cx = 0f
    private var cy = 0f
    private var sCx = 0f
    private var sCy = 0f
    private var ox = 0
    private var oy = 0
    private var sw = 0
    private var sh = 0
    private var dragOn = false
    private var minimized = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        pad.alpha = alpha()
        if (key == "size") {
            val s = dp(prefs.getInt("size", 200))
            padLp.width = s; padLp.height = s
            padLp.x = padLp.x.coerceIn(0, maxOf(0, sw - s)); padLp.y = padLp.y.coerceIn(0, maxOf(0, sh - s))
            if (!minimized) wm.updateViewLayout(pad, padLp)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun alpha() = prefs.getInt("alpha", 95) / 100f

    private fun lp(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        return params
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("sandpad", MODE_PRIVATE)
        val b = wm.maximumWindowMetrics.bounds
        sw = b.width(); sh = b.height()
        cx = sw / 2f; cy = sh / 4f

        cursorView = CursorView(this)
        wm.addView(cursorView, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, false))
        cursorView.setPos(cx, cy)

        val size = dp(prefs.getInt("size", 200))
        pad = PadView(this, this)
        pad.alpha = alpha()
        padLp = lp(size, size, true)
        padLp.x = prefs.getInt("x", dp(40)).coerceIn(0, maxOf(0, sw - size))
        padLp.y = prefs.getInt("y", dp(90)).coerceIn(0, maxOf(0, sh - size))

        val bs = dp(50)
        bubble = TextView(this).apply {
            text = "◉"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#c3592b")); setStroke(dp(3), Color.parseColor("#f0e0d0")) }
        }
        bubbleLp = lp(bs, bs, true)
        bubbleLp.x = prefs.getInt("bx", dp(16)); bubbleLp.y = prefs.getInt("by", dp(120))
        var sx = 0f; var sy = 0f; var bx = 0; var by = 0; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; bx = bubbleLp.x; by = bubbleLp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) + abs(e.rawY - sy) > dp(8)) moved = true
                    if (moved) {
                        bubbleLp.x = (bx + e.rawX - sx).toInt().coerceIn(0, sw - bs)
                        bubbleLp.y = (by + e.rawY - sy).toInt().coerceIn(0, sh - bs)
                        wm.updateViewLayout(bubble, bubbleLp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("bx", bubbleLp.x).putInt("by", bubbleLp.y).apply()
                    if (!moved) bubble.post { setMinimized(false) }
                }
            }
            true
        }
        wm.addView(pad, padLp)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun setMinimized(m: Boolean) {
        if (m == minimized) return
        minimized = m
        if (m) { wm.removeView(pad); wm.addView(bubble, bubbleLp) }
        else { wm.removeView(bubble); wm.addView(pad, padLp) }
    }

    // ---- PadView.Host ----
    override fun gain() = prefs.getInt("speed", 18) / 10f
    override fun isDragOn() = dragOn
    override fun toggleDrag() { dragOn = !dragOn; pad.invalidate() }
    override fun beginPad() { sCx = cx; sCy = cy }

    override fun moveCursor(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(0f, sw - 1f)
        cy = (cy + dy).coerceIn(0f, sh - 1f)
        cursorView.setPos(cx, cy)
    }

    override fun tap(long: Boolean) { gesture(cx, cy, cx, cy, if (long) 700 else 40) }

    override fun padRelease(dt: Long) {
        if (dragOn) {
            gesture(sCx, sCy, cx, cy, dt.coerceIn(200L, 1500L))
            dragOn = false; pad.invalidate()
        }
    }

    override fun swipe(dir: Int) {
        val dv = sh * 0.3f
        val dh = sw * 0.35f
        val v = (cy - dv / 2).coerceIn(0f, sh - dv)
        val h = (cx - dh / 2).coerceIn(0f, sw - dh)
        when (dir) {
            0 -> gesture(cx, v, cx, v + dv, 250)        // scroll up
            2 -> gesture(cx, v + dv, cx, v, 250)        // scroll down
            1 -> gesture(h + dh, cy, h, cy, 250)        // scroll right
            3 -> gesture(h, cy, h + dh, cy, 250)        // scroll left
        }
    }

    override fun action(a: Int) { performGlobalAction(a) }
    override fun minimize() { pad.post { setMinimized(true) } }
    override fun beginMove() { ox = padLp.x; oy = padLp.y }

    override fun moveWindow(dx: Float, dy: Float) {
        padLp.x = (ox + dx).toInt().coerceIn(0, maxOf(0, sw - padLp.width))
        padLp.y = (oy + dy).toInt().coerceIn(0, maxOf(0, sh - padLp.height))
        wm.updateViewLayout(pad, padLp)
    }

    override fun savePosition() { prefs.edit().putInt("x", padLp.x).putInt("y", padLp.y).apply() }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val p = Path()
        p.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2)
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(),
            null, null
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        try { wm.removeView(cursorView) } catch (_: Exception) {}
        try { wm.removeView(pad) } catch (_: Exception) {}
        try { wm.removeView(bubble) } catch (_: Exception) {}
        super.onDestroy()
    }
}
