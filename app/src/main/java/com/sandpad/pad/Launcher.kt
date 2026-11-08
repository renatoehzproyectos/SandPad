package com.sandpad.pad

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Floating fast-launch panel: pinned apps on top, type to search every app. Lives in an overlay, never opens SandPad. */
class LauncherPanel(
    private val svc: AccessibilityService,
    private val wm: WindowManager,
    private val prefs: SharedPreferences
) {
    private val sand = Color.parseColor("#f0e0d0")
    private val sand2 = Color.parseColor("#e7ceb9")
    private val clay = Color.parseColor("#d69264")
    private val rust = Color.parseColor("#c3592b")
    private val d = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var root: LinearLayout? = null
    private var grid: LinearLayout? = null
    private var search: EditText? = null
    private var apps: List<AppsActivity.App> = emptyList()

    fun isShown() = root != null

    fun hide() {
        val r = root ?: return
        try { wm.removeView(r) } catch (_: Exception) {}
        root = null; grid = null; search = null
    }

    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** Placed in the free space below or above the pad (inside the touchable top half); overlaps only if there is no room. */
    fun show(sw: Int, sh: Int, padTop: Int, padBottom: Int) {
        if (root != null) return
        if (apps.isEmpty()) apps = AppsActivity.load(svc.packageManager)

        val margin = dp(28)
        val limit = (sh * 0.5f).toInt() - dp(8)
        val maxH = (sh * 0.4f).toInt()
        val above = padTop - margin - dp(6)
        val below = limit - padBottom - dp(6)
        val h: Int
        val y: Int
        if (below >= dp(170) && below >= above) { h = minOf(below, maxH); y = padBottom + dp(6) }
        else if (above >= dp(170)) { h = minOf(above, maxH); y = margin }
        else { h = maxH; y = margin }
        val w = (sw * 0.94f).toInt()

        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = round(sand, 22, clay)
        }
        val et = EditText(svc).apply {
            hint = "Search apps"; isSingleLine = true; textSize = 15f
            setTextColor(Color.BLACK); setHintTextColor(Color.GRAY)
            background = round(sand2, 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(svc).apply {
            text = "✕"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = round(rust, 20)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) }
            setOnClickListener { hide() }
        }
        val topRow = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL; addView(et); addView(close) }
        val g = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(svc).apply {
            addView(g)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) }
        }
        box.addView(topRow); box.addView(scroll)
        root = box; grid = g; search = et

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        render()

        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (sw - w) / 2; lp.y = y
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(box, lp)
    }

    private fun render() {
        val g = grid ?: return
        val q = (search?.text?.toString() ?: "").trim().lowercase()
        g.removeAllViews()
        val list: List<AppsActivity.App>
        if (q.isEmpty()) {
            val pins = (prefs.getString("pinned", "") ?: "").split("\n").filter { it.isNotEmpty() }
            list = pins.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
            if (list.isEmpty()) {
                g.addView(TextView(svc).apply {
                    text = "No pinned apps yet. Open SandPad → Pinned apps to add some. You can still type above to search."
                    textSize = 13f; setTextColor(Color.DKGRAY); setPadding(dp(4), dp(8), dp(4), dp(8))
                })
            }
        } else {
            list = apps.filter { it.label.lowercase().contains(q) }.take(40)
        }
        for (chunk in list.chunked(4)) {
            val row = LinearLayout(svc).apply { weightSum = 4f }
            for (a in chunk) row.addView(cell(a))
            g.addView(row)
        }
    }

    private fun cell(a: AppsActivity.App): View = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(8), dp(2), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(ImageView(svc).apply {
            setImageDrawable(a.icon); layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        })
        addView(TextView(svc).apply {
            text = a.label; textSize = 10.5f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        setOnClickListener {
            val i = svc.packageManager.getLaunchIntentForPackage(a.pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                hide()
                svc.startActivity(i)
            }
        }
    }
}
