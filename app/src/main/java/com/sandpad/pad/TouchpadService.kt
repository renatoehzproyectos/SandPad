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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class TouchpadService : AccessibilityService() {
    private val sand = Color.parseColor("#f0e0d0")
    private val sand2 = Color.parseColor("#e7ceb9")
    private val sand3 = Color.parseColor("#e1be9b")
    private val clay = Color.parseColor("#d69264")
    private val rust = Color.parseColor("#c3592b")

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var cursor: View
    private lateinit var pad: View
    private lateinit var bubble: View
    private lateinit var cursorLp: WindowManager.LayoutParams
    private lateinit var padLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var dragBtn: TextView
    private var cx = 0f
    private var cy = 0f
    private var sw = 0
    private var sh = 0
    private var dragMode = false
    private var minimized = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> pad.alpha = alpha() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun alpha() = prefs.getInt("alpha", 90) / 100f
    private fun speed() = prefs.getInt("speed", 18) / 10f

    private fun lp(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        val f = WindowManager.LayoutParams
        return WindowManager.LayoutParams(
            w, h, f.TYPE_ACCESSIBILITY_OVERLAY,
            f.FLAG_NOT_FOCUSABLE or f.FLAG_LAYOUT_NO_LIMITS or f.FLAG_LAYOUT_IN_SCREEN or
                (if (touchable) 0 else f.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun shape(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun btn(label: String, bg: Int = sand3, fg: Int = Color.BLACK, onTap: () -> Unit) =
        TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 17f; setTextColor(fg)
            background = shape(bg, 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            setOnClickListener { onTap() }
        }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("sandpad", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        val b = wm.currentWindowMetrics.bounds
        sw = b.width(); sh = b.height()
        cx = sw / 2f; cy = sh / 4f

        val r = dp(11)
        cursor = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(rust); setStroke(dp(2), Color.WHITE) } }
        cursorLp = lp(r * 2, r * 2, false)
        wm.addView(cursor, cursorLp); moveCursor(0f, 0f)

        buildPad(); buildBubble()
        wm.addView(pad, padLp)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(0f, sw - 1f); cy = (cy + dy).coerceIn(0f, sh - 1f)
        cursorLp.x = (cx - dp(11)).toInt(); cursorLp.y = (cy - dp(11)).toInt()
        wm.updateViewLayout(cursor, cursorLp)
    }

    private fun buildPad() {
        val handle = TextView(this).apply {
            text = "━━━"; gravity = Gravity.CENTER; setTextColor(sand3); background = shape(Color.BLACK, 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
        }
        val resize = TextView(this).apply {
            text = "⤡"; gravity = Gravity.CENTER; setTextColor(Color.BLACK); background = shape(sand3, 14)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(34)).apply { leftMargin = dp(6) }
        }
        val mini = TextView(this).apply {
            text = "–"; gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE); background = shape(rust, 14)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(34)).apply { leftMargin = dp(6) }
            setOnClickListener { setMinimized(true) }
        }
        val header = LinearLayout(this).apply { addView(handle); addView(resize); addView(mini) }

        val surface = View(this).apply {
            background = shape(sand2, 16, clay)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(5) }
        }
        val row1 = LinearLayout(this).apply {
            addView(btn("◀") { performGlobalAction(GLOBAL_ACTION_BACK) })
            addView(btn("●", rust, Color.WHITE) { performGlobalAction(GLOBAL_ACTION_HOME) })
            addView(btn("▣") { performGlobalAction(GLOBAL_ACTION_RECENTS) })
        }
        dragBtn = btn("✥") { dragMode = !dragMode; refreshDrag() }
        val row2 = LinearLayout(this).apply {
            addView(btn("≡") { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) })
            addView(btn("⚙") { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) })
            addView(btn("▲") { scroll(true) })
            addView(btn("▼") { scroll(false) })
            addView(dragBtn)
        }
        pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(5))
            background = shape(sand, 24, clay)
            alpha = alpha()
            addView(header); addView(surface); addView(row1); addView(row2)
        }
        padLp = lp(prefs.getInt("w", dp(290)), prefs.getInt("h", dp(330)), true).apply {
            x = prefs.getInt("x", dp(40)); y = prefs.getInt("y", dp(60))
        }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = padLp.x; oy = padLp.y }
                MotionEvent.ACTION_MOVE -> {
                    padLp.x = (ox + e.rawX - sx).toInt().coerceIn(0, maxOf(0, sw - padLp.width))
                    padLp.y = (oy + e.rawY - sy).toInt().coerceIn(0, maxOf(0, sh - padLp.height))
                    wm.updateViewLayout(pad, padLp)
                }
                MotionEvent.ACTION_UP -> savePad()
            }; true
        }
        var ow = 0; var oh = 0
        resize.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ow = padLp.width; oh = padLp.height }
                MotionEvent.ACTION_MOVE -> {
                    padLp.width = (ow + e.rawX - sx).toInt().coerceIn(dp(230), sw - padLp.x)
                    padLp.height = (oh + e.rawY - sy).toInt().coerceIn(dp(270), sh - padLp.y)
                    wm.updateViewLayout(pad, padLp)
                }
                MotionEvent.ACTION_UP -> savePad()
            }; true
        }

        var lx = 0f; var ly = 0f; var total = 0f; var t0 = 0L; var sCx = 0f; var sCy = 0f
        surface.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; total = 0f; t0 = e.eventTime; sCx = cx; sCy = cy }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lx; val dy = e.y - ly
                    total += abs(dx) + abs(dy); lx = e.x; ly = e.y
                    moveCursor(dx * speed(), dy * speed())
                }
                MotionEvent.ACTION_UP -> {
                    val dt = e.eventTime - t0
                    if (total < dp(8)) {
                        if (dt < 350) gesture(cx, cy, cx, cy, 40) else gesture(cx, cy, cx, cy, 700)
                    } else if (dragMode) {
                        gesture(sCx, sCy, cx, cy, dt.coerceIn(200, 1500))
                    }
                    if (dragMode) { dragMode = false; refreshDrag() }
                }
            }; true
        }
    }

    private fun buildBubble() {
        val s = dp(56)
        bubble = TextView(this).apply {
            text = "◉"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(rust); setStroke(dp(3), sand) }
        }
        bubbleLp = lp(s, s, true).apply { x = prefs.getInt("bx", dp(16)); y = prefs.getInt("by", dp(120)) }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = bubbleLp.x; oy = bubbleLp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) + abs(e.rawY - sy) > dp(8)) moved = true
                    if (moved) {
                        bubbleLp.x = (ox + e.rawX - sx).toInt().coerceIn(0, sw - s)
                        bubbleLp.y = (oy + e.rawY - sy).toInt().coerceIn(0, sh - s)
                        wm.updateViewLayout(bubble, bubbleLp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("bx", bubbleLp.x).putInt("by", bubbleLp.y).apply()
                    if (!moved) setMinimized(false)
                }
            }; true
        }
    }

    private fun setMinimized(m: Boolean) {
        if (m == minimized) return
        minimized = m
        if (m) { wm.removeView(pad); wm.addView(bubble, bubbleLp) }
        else { wm.removeView(bubble); wm.addView(pad, padLp) }
    }

    private fun refreshDrag() {
        dragBtn.background = shape(if (dragMode) rust else sand3, 14)
        dragBtn.setTextColor(if (dragMode) Color.WHITE else Color.BLACK)
    }

    private fun savePad() = prefs.edit().putInt("x", padLp.x).putInt("y", padLp.y)
        .putInt("w", padLp.width).putInt("h", padLp.height).apply()

    private fun scroll(up: Boolean) {
        val d = sh * 0.3f
        val a = (cy - d / 2).coerceIn(0f, sh - d); val b = a + d
        if (up) gesture(cx, a, cx, b, 250) else gesture(cx, b, cx, a, 250)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(), null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        for (v in listOf(cursor, pad, bubble)) try { wm.removeView(v) } catch (_: Exception) {}
        super.onDestroy()
    }
}
