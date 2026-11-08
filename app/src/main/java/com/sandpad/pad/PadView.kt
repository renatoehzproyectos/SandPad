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

/** Round pad + customizable ring + the arc menu that opens above it (same window, so no touch conflicts). */
class PadView(ctx: Context, private val host: Host) : View(ctx) {
    interface Host {
        fun layout(): List<Btn>
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
        fun padPx(): Int
        fun menuOpen(): Boolean
        fun offX(): Int
        fun offY(): Int
        fun toggleMenu()
        fun menuAction(i: Int)
    }

    companion object {
        const val HALF = 50f
        fun bandIn(pad: Int, d: Float) = pad / 2f + 6f * d
        fun bandRow(pad: Int) = pad * 0.15f
        fun bandOut(pad: Int, d: Float) = bandIn(pad, d) + 2f * bandRow(pad)
        fun menuExtra(pad: Int, d: Float) = (bandOut(pad, d) - pad / 2f + 8f * d).toInt()
        fun menuWidth(pad: Int, d: Float): Int {
            val half = Math.toRadians(HALF.toDouble())
            val w = 2f * bandOut(pad, d) * sin(half).toFloat() + 12f * d
            return maxOf(pad, w.toInt())
        }
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val painter = RingPainter(density)
    private val sand2 = Color.parseColor("#e7ceb9")
    private val sand3 = Color.parseColor("#e1be9b")
    private val clay = Color.parseColor("#d69264")
    private val cellFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; pathEffect = CornerPathEffect(dp(6f)) }
    private val cellTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val cellPath = Path()
    private val rectA = RectF()
    private val rectB = RectF()
    private val cellLabels = arrayOf("◧", "✦", "3", "4", "5", "6")

    private var buttons: List<Btn> = host.layout()
    private val handler = Handler(Looper.getMainLooper())
    private val repeater = object : Runnable {
        override fun run() {
            val b = pressedBtn
            if (b != null) fire(b.type)
            handler.postDelayed(this, 260)
        }
    }
    private var pressedBtn: Btn? = null
    private var pressedCell = -1
    private var mode = 0 // 1 = touchpad, 2 = ring button, 3 = moving pad, 4 = menu cell
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var total = 0f
    private var t0 = 0L

    fun reload() { buttons = host.layout(); invalidate() }

    private fun isArrow(t: String) = t == "up" || t == "down" || t == "left" || t == "right"

    override fun onDraw(c: Canvas) {
        val pad = host.padPx()
        c.save()
        c.translate(host.offX().toFloat(), host.offY().toFloat())
        painter.draw(c, pad, pad, buttons, pressedBtn, host.isDragOn(), -1, false)
        if (host.menuOpen()) drawMenu(c, pad)
        c.restore()
    }

    private fun drawMenu(c: Canvas, pad: Int) {
        val cx = pad / 2f
        val cy = pad / 2f
        val r1 = bandIn(pad, density)
        val rh = bandRow(pad)
        val g = dp(1.5f)
        val col = 2f * HALF / 3f
        cellTxt.textSize = rh * 0.6f
        for (i in 0 until 6) {
            val c0 = i / 2
            val outer = i % 2 == 0
            val ra = if (outer) r1 + rh + g else r1
            val rb = if (outer) r1 + 2f * rh else r1 + rh - g
            val a0 = -90f - HALF + col * c0 + 1.5f
            val sweep = col - 3f
            rectA.set(cx - rb, cy - rb, cx + rb, cy + rb)
            rectB.set(cx - ra, cy - ra, cx + ra, cy + ra)
            cellPath.reset()
            cellPath.arcTo(rectA, a0, sweep)
            cellPath.arcTo(rectB, a0 + sweep, -sweep)
            cellPath.close()
            cellFill.color = if (i == pressedCell) clay else if (i < 2) sand3 else sand2
            c.drawPath(cellPath, cellFill)
            val am = Math.toRadians(-90.0 - HALF + col * (c0 + 0.5))
            val rm = (ra + rb) / 2f
            cellTxt.color = if (i < 2) Color.BLACK else clay
            val tx = cx + (rm * cos(am)).toFloat()
            val ty = cy + (rm * sin(am)).toFloat() - (cellTxt.ascent() + cellTxt.descent()) / 2f
            c.drawText(cellLabels[i], tx, ty, cellTxt)
        }
    }

    private fun menuCell(dx: Float, dy: Float, pad: Int): Int {
        val d = hypot(dx, dy)
        val r1 = bandIn(pad, density)
        val rh = bandRow(pad)
        if (d < r1 || d > r1 + 2f * rh) return -1
        val ang = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        if (abs(ang) > HALF) return -1
        val col = ((ang + HALF) / (2f * HALF / 3f)).toInt().coerceIn(0, 2)
        return col * 2 + (if (d > r1 + rh) 0 else 1)
    }

    private fun fire(t: String) {
        when (t) {
            "up" -> host.swipe(0)
            "right" -> host.swipe(1)
            "down" -> host.swipe(2)
            "left" -> host.swipe(3)
            "drag" -> host.toggleDrag()
            "window" -> host.toggleMenu()
            "recents" -> host.action(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "home" -> host.action(AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> host.action(AccessibilityService.GLOBAL_ACTION_BACK)
            "qs" -> host.action(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "min" -> host.minimize()
        }
    }

    private fun buzz() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = host.padPx()
        val px = e.x - host.offX()
        val py = e.y - host.offY()
        val cx = pad / 2f
        val cy = pad / 2f
        val r = pad / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dx = px - cx
                val dy = py - cy
                val dist = hypot(dx, dy)
                if (dist > r) {
                    val cell = if (host.menuOpen()) menuCell(dx, dy, pad) else -1
                    if (cell < 0) return false
                    mode = 4; pressedCell = cell
                    buzz(); invalidate()
                    return true
                }
                if (dist <= r * 0.62f - dp(6f)) {
                    mode = 1; lx = px; ly = py; total = 0f; t0 = e.eventTime
                    host.beginPad()
                } else {
                    val slot = Ring.slotAt(dx, dy)
                    val b = buttons.firstOrNull { it.covers(slot) }
                    pressedBtn = b
                    if (b == null || b.type == "move") {
                        mode = 3; rx = e.rawX; ry = e.rawY
                        host.beginMove()
                    } else {
                        mode = 2
                        buzz()
                        if (isArrow(b.type)) { fire(b.type); handler.postDelayed(repeater, 450) }
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1) {
                    val dx = px - lx
                    val dy = py - ly
                    lx = px; ly = py
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
                var menuHit = -1
                if (mode == 1 && up) {
                    val dt = e.eventTime - t0
                    if (total < dp(8f)) host.tap(dt >= 350) else host.padRelease(dt)
                } else if (mode == 2 && up) {
                    val pb = pressedBtn
                    if (pb != null && !isArrow(pb.type) && pb.covers(Ring.slotAt(px - cx, py - cy))) fire(pb.type)
                } else if (mode == 3) {
                    host.savePosition()
                } else if (mode == 4 && up && menuCell(px - cx, py - cy, pad) == pressedCell) {
                    menuHit = pressedCell
                }
                mode = 0; pressedBtn = null; pressedCell = -1
                invalidate()
                if (menuHit >= 0) host.menuAction(menuHit)
            }
        }
        return true
    }
}
