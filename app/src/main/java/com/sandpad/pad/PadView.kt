package com.sandpad.pad

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Round pad: touch surface in the middle, customizable button ring around it. Empty ring area drags the pad. */
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
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val painter = RingPainter(density)
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
    private var mode = 0 // 1 = touchpad, 2 = ring button, 3 = moving the pad
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var total = 0f
    private var t0 = 0L

    fun reload() { buttons = host.layout(); invalidate() }

    private fun isArrow(t: String) = t == "up" || t == "down" || t == "left" || t == "right"

    override fun onDraw(c: Canvas) {
        painter.draw(c, width, height, buttons, pressedBtn, host.isDragOn(), -1, false)
    }

    private fun fire(t: String) {
        when (t) {
            "up" -> host.swipe(0)
            "right" -> host.swipe(1)
            "down" -> host.swipe(2)
            "left" -> host.swipe(3)
            "drag" -> host.toggleDrag()
            "recents" -> host.action(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "home" -> host.action(AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> host.action(AccessibilityService.GLOBAL_ACTION_BACK)
            "qs" -> host.action(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "min" -> host.minimize()
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
                } else if (mode == 2 && up) {
                    val pb = pressedBtn
                    if (pb != null && !isArrow(pb.type) && pb.covers(Ring.slotAt(e.x - cx, e.y - cy))) fire(pb.type)
                } else if (mode == 3) {
                    host.savePosition()
                }
                mode = 0; pressedBtn = null
                invalidate()
            }
        }
        return true
    }
}
