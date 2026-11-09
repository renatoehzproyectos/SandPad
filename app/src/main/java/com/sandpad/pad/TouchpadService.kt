package com.sandpad.pad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class TouchpadService : AccessibilityService(), PadView.Host {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var cursorView: CursorView
    private lateinit var pad: PadView
    private lateinit var bubble: TextView
    private lateinit var padLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var cursorLp: WindowManager.LayoutParams
    private lateinit var launcher: LauncherPanel
    private var cx = 0f
    private var cy = 0f
    private var sCx = 0f
    private var sCy = 0f
    private var padX = 0
    private var padY = 0
    private var ox = 0
    private var oy = 0
    private var mOffX = 0
    private var mOffY = 0
    private var sw = 0
    private var sh = 0
    private var dragOn = false
    private var minimized = false
    private var menuOn = false
    private var attached = false
    private var lastApp: String? = null
    private var homePkg: String? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        pad.alpha = alpha()
        if (key == "layout") pad.reload()
        if (key == "size") { clampPad(); applyWindow() }
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

    /** Screen size forced to the current orientation, so a portrait phone never gets landscape-shaped walls. */
    private fun refreshBounds() {
        val b = wm.maximumWindowMetrics.bounds
        val dm = resources.displayMetrics
        val longSide = maxOf(b.width(), b.height(), dm.widthPixels, dm.heightPixels)
        val shortSide = maxOf(minOf(b.width(), b.height()), minOf(dm.widthPixels, dm.heightPixels))
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        if (portrait) { sw = shortSide; sh = longSide } else { sw = longSide; sh = shortSide }
    }

    private fun clampPad() {
        val s = padPx()
        padX = padX.coerceIn(0, maxOf(0, sw - s))
        padY = padY.coerceIn(0, maxOf(0, sh - s))
    }

    /** Pad window = just the pad, or the pad plus room above for the arc menu. */
    private fun applyWindow() {
        val size = padPx()
        val d = resources.displayMetrics.density
        if (menuOn) {
            val w = PadView.menuWidth(size, d)
            val extra = PadView.menuExtra(size, d)
            padY = padY.coerceAtLeast(extra)
            val wx = (padX + size / 2 - w / 2).coerceIn(0, maxOf(0, sw - w))
            padLp.width = w; padLp.height = size + extra
            padLp.x = wx; padLp.y = padY - extra
            mOffX = padX - wx; mOffY = extra
        } else {
            padLp.width = size; padLp.height = size
            padLp.x = padX; padLp.y = padY
            mOffX = 0; mOffY = 0
        }
        if (attached && !minimized) wm.updateViewLayout(pad, padLp)
        if (attached) pad.invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!attached) return
        refreshBounds()
        cx = cx.coerceIn(0f, sw - 1f); cy = cy.coerceIn(0f, sh - 1f)
        cursorView.setPos(cx, cy)
        clampPad(); applyWindow()
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("sandpad", MODE_PRIVATE)
        launcher = LauncherPanel(this, wm, prefs)
        homePkg = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        refreshBounds()
        cx = sw / 2f; cy = sh / 4f

        cursorView = CursorView(this)
        cursorLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, false)
        wm.addView(cursorView, cursorLp)
        cursorView.setPos(cx, cy)

        pad = PadView(this, this)
        pad.alpha = alpha()
        padLp = lp(padPx(), padPx(), true)
        padX = prefs.getInt("x", dp(40)); padY = prefs.getInt("y", dp(90))
        clampPad(); applyWindow()

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
        attached = true
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun setMinimized(m: Boolean) {
        if (m == minimized) return
        minimized = m
        if (m) { wm.removeView(pad); wm.addView(bubble, bubbleLp) }
        else { wm.removeView(bubble); wm.addView(pad, padLp) }
    }

    // ---- PadView.Host ----
    override fun layout() = Ring.load(prefs)
    override fun gain() = prefs.getInt("speed", 12) / 10f
    override fun padPx() = dp(prefs.getInt("size", 230))
    override fun menuOpen() = menuOn
    override fun offX() = mOffX
    override fun offY() = mOffY
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
            0 -> gesture(cx, v, cx, v + dv, 250)
            2 -> gesture(cx, v + dv, cx, v, 250)
            1 -> gesture(h + dh, cy, h, cy, 250)
            3 -> gesture(h, cy, h + dh, cy, 250)
        }
    }

    override fun action(a: Int) { performGlobalAction(a) }

    override fun minimize() {
        pad.post {
            if (menuOn) { menuOn = false; applyWindow() }
            setMinimized(true)
        }
    }

    override fun beginMove() { ox = padX; oy = padY }

    override fun moveWindow(dx: Float, dy: Float) {
        val s = padPx()
        padX = (ox + dx).toInt().coerceIn(0, maxOf(0, sw - s))
        padY = (oy + dy).toInt().coerceIn(0, maxOf(0, sh - s))
        applyWindow()
    }

    override fun savePosition() { prefs.edit().putInt("x", padX).putInt("y", padY).apply() }

    override fun toggleMenu() { menuOn = !menuOn; applyWindow() }

    override fun menuAction(i: Int) {
        menuOn = false
        applyWindow()
        when (i) {
            0 -> startSplit()
            1 -> pad.post {
                if (launcher.isShown()) launcher.hide()
                else { launcher.show(sw, sh, padY, padY + padPx()); restack() }
            }
            else -> Toast.makeText(this, "Menu slot ${i + 1} is free", Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-adds the cursor and pad so they always sit above the launcher panel. */
    private fun restack() {
        try { wm.removeView(cursorView) } catch (_: Exception) {}
        wm.addView(cursorView, cursorLp)
        if (minimized) {
            try { wm.removeView(bubble) } catch (_: Exception) {}
            wm.addView(bubble, bubbleLp)
        } else {
            try { wm.removeView(pad) } catch (_: Exception) {}
            wm.addView(pad, padLp)
        }
    }

    private fun say(t: String) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    private fun findNode(n: AccessibilityNodeInfo?, key: String): AccessibilityNodeInfo? {
        if (n == null) return null
        val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
        if (t.contains(key) && n.packageName?.toString() != packageName) return n
        for (i in 0 until n.childCount) {
            val r = findNode(n.getChild(i), key)
            if (r != null) return r
        }
        return null
    }

    private fun findAny(key: String): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        for (w in windows) { val r = w.root; if (r != null) roots.add(r) }
        for (r in roots) { val n = findNode(r, key); if (n != null) return n }
        return null
    }

    private fun clickUp(start: AccessibilityNodeInfo?): Boolean {
        var c = start
        var depth = 0
        while (c != null && depth < 8) {
            if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            c = c.parent
            depth++
        }
        return false
    }

    /** Tries to find and click an on-screen item whose text matches any key; calls done(true/false). */
    private fun clickText(keys: Array<String>, tries: Int, gap: Long, done: (Boolean) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        var n = 0
        val r = object : Runnable {
            override fun run() {
                for (k in keys) {
                    val node = findAny(k)
                    if (node != null && clickUp(node)) { done(true); return }
                }
                n++
                if (n >= tries) done(false) else h.postDelayed(this, gap)
            }
        }
        h.post(r)
    }

    private fun padTouchable(on: Boolean) {
        if (!attached || minimized) return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        padLp.flags = if (on) padLp.flags and f.inv() else padLp.flags or f
        wm.updateViewLayout(pad, padLp)
    }

    /**
     * The same steps you do by hand: Recents, tap the current app's icon, choose "Split screen",
     * then open SandPad's plain colour screen as the second app. Press again to close it.
     */
    private fun startSplit() {
        val bottom: android.app.Activity? =
            SplitActivity.instance?.takeIf { it.isInMultiWindowMode } ?: MainActivity.splitInstance
        if (bottom != null) {
            say("Closing split screen")
            bottom.finish()
            return
        }
        val h = Handler(Looper.getMainLooper())
        padTouchable(false)
        say("Split 1/3: opening recents")
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        h.postDelayed({
            // the icon above the centred (current app) card
            gesture(sw * 0.494f, sh * 0.08f, sw * 0.494f, sh * 0.08f, 60)
        }, 1100)
        h.postDelayed({
            say("Split 2/3: choosing the option")
            clickText(arrayOf("pantalla dividida", "split screen", "split top", "dividir"), 5, 400) { found ->
                if (!found) gesture(sw * 0.5f, sh * 0.235f, sw * 0.5f, sh * 0.235f, 60)
                h.postDelayed({
                    say("Split 3/3: choosing the second app")
                    val node = findAny("sandpad")
                    if (node != null) {
                        // tap SandPad's icon on the home screen, exactly like doing it by hand
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
                    } else {
                        startActivity(Intent(this, SplitActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
                    }
                    h.postDelayed({ padTouchable(true) }, 800)
                }, 1200)
            }
        }, 1900)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val p = Path()
        p.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2)
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(),
            null, null
        )
    }

    /** Remembers the last normal app that was on screen: that is the one split screen will keep on top. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (p == packageName || p == homePkg) return
        if (packageManager.getLaunchIntentForPackage(p) != null) lastApp = p
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::launcher.isInitialized) launcher.hide()
        try { wm.removeView(cursorView) } catch (_: Exception) {}
        try { wm.removeView(pad) } catch (_: Exception) {}
        try { wm.removeView(bubble) } catch (_: Exception) {}
        super.onDestroy()
    }
}
