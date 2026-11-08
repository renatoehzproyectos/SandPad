package com.sandpad.pad

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Round pad: touch surface in the middle, 10 button segments in a ring around it. */
class PadView(ctx: Context, private val host: Host) : View(ctx) {
    interface Host {
        fun gain(): Float
        fun isDragOn(): Boolean
        fun toggleDrag()
        fun beginPad()
        fun moveCursor(dx: Float, dy: Float)
        fun tap(long: Boolean)
        fun padRelease(dt: Long)
        fun swipe(dir: Int)
        fun action(a: Int)
        fun minimize()
        fun beginMove()
        fun moveWindow(dx: Float, dy: Float)
        fun savePosition()
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val sand = Color.parseColor("#f0e0d0")
    private val sand2 = Color.parseColor("#e7ceb9")
    private val sand3 = Color.parseColor("#e1be9b")
    private val clay = Color.parseColor("#d69264")
    private val rust = Color.parseColor("#c3592b")

    // clockwise from the top: 0 up, 1 right, 2 down, 3 drag, 4 recents, 5 home, 6 back, 7 move, 8 left, 9 minimize
    private val labels = arrayOf("↑", "→", "↓", "✥", "▣", "●", "◀", "≡", "←", "–")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; pathEffect = CornerPathEffect(dp(5f)) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = clay }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val outer = RectF()
    private val inner = RectF()
    private val path = Path()

    private val handler = Handler(Looper.getMainLooper())
    private val repeater = object : Runnable {
        override fun run() { fire(pressed); handler.postDelayed(this, 260) }
    }
    private var pressed = -1
    private var mode = 0 // 1 = touchpad, 2 = ring button, 3 = moving the pad
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var total = 0f
    private var t0 = 0L

    private fun isArrow(k: Int) = k == 0 || k == 1 || k == 2 || k == 8

    private fun segment(dx: Float, dy: Float): Int {
        val ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val rel = (ang + 90f + 18f + 720f) % 360f
        return (rel / 36f).toInt() % 10
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(1.5f)
        fill.color = sand
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, line)
        val rOut = r - dp(5f)
        val rIn = r * 0.62f
        outer.set(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        inner.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn)
        txt.textSize = r * 0.2f
        val dragOn = host.isDragOn()
        for (k in 0 until 10) {
            val a0 = -90f + 36f * k - 18f + 1.5f
            val sweep = 33f
            path.reset()
            path.arcTo(outer, a0, sweep)
            path.arcTo(inner, a0 + sweep, -sweep)
            path.close()
            val hot = k == 5 || (k == 3 && dragOn)
            fill.color = if (k == pressed) clay else if (hot) rust else sand3
            c.drawPath(path, fill)
            val am = Math.toRadians(-90.0 + 36.0 * k)
            val rm = (rOut + rIn) / 2f
            txt.color = if (hot) Color.WHITE else Color.BLACK
            val tx = cx + (rm * cos(am)).toFloat()
            val ty = cy + (rm * sin(am)).toFloat() - (txt.ascent() + txt.descent()) / 2f
            c.drawText(labels[k], tx, ty, txt)
        }
        fill.color = sand2
        c.drawCircle(cx, cy, rIn - dp(6f), fill)
        c.drawCircle(cx, cy, rIn - dp(6f), line)
    }

    private fun fire(k: Int) {
        when (k) {
            0 -> host.swipe(0)
            1 -> host.swipe(1)
            2 -> host.swipe(2)
            8 -> host.swipe(3)
            3 -> host.toggleDrag()
            4 -> host.action(AccessibilityService.GLOBAL_ACTION_RECENTS)
            5 -> host.action(AccessibilityService.GLOBAL_ACTION_HOME)
            6 -> host.action(AccessibilityService.GLOBAL_ACTION_BACK)
            9 -> host.minimize()
        }
    }

    private fun buzz() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dx = e.x - cx
                val dy = e.y - cy
                val dist = hypot(dx, dy)
                if (dist > r) return false
                if (dist <= r * 0.62f - dp(6f)) {
                    mode = 1; lx = e.x; ly = e.y; total = 0f; t0 = e.eventTime
                    host.beginPad()
                } else {
                    pressed = segment(dx, dy)
                    buzz()
                    if (pressed == 7) {
                        mode = 3; rx = e.rawX; ry = e.rawY
                        host.beginMove()
                    } else {
                        mode = 2
                        if (isArrow(pressed)) { fire(pressed); handler.postDelayed(repeater, 450) }
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1) {
                    val dx = e.x - lx
                    val dy = e.y - ly
                    lx = e.x; ly = e.y
                    total += abs(dx) + abs(dy)
                    val g = host.gain() * (1f + min(hypot(dx, dy) / dp(5f), 1.5f))
                    host.moveCursor(dx * g, dy * g)
                } else if (mode == 3) {
                    host.moveWindow(e.rawX - rx, e.rawY - ry)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeater)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (mode == 1 && up) {
                    val dt = e.eventTime - t0
                    if (total < dp(8f)) host.tap(dt >= 350) else host.padRelease(dt)
                } else if (mode == 2 && up && !isArrow(pressed) && segment(e.x - cx, e.y - cy) == pressed) {
                    fire(pressed)
                } else if (mode == 3) {
                    host.savePosition()
                }
                mode = 0; pressed = -1
                invalidate()
            }
        }
        return true
    }
}
