package com.sandpad.pad

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Search all apps, pin favourites. In launch mode (from the pad menu) tapping an app opens it. */
class AppsActivity : Activity() {
    class App(val pkg: String, val label: String, val icon: Drawable)

    private val sand = Color.parseColor("#f0e0d0")
    private val sand2 = Color.parseColor("#e7ceb9")
    private val rust = Color.parseColor("#c3592b")
    private lateinit var prefs: SharedPreferences
    private lateinit var search: EditText
    private lateinit var pinnedTitle: TextView
    private lateinit var pinnedBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private var apps: List<App> = emptyList()
    private var launchMode = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pinned(): MutableList<String> =
        (prefs.getString("pinned", "") ?: "").split("\n").filter { it.isNotEmpty() }.toMutableList()

    private fun label(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(4))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("sandpad", MODE_PRIVATE)
        launchMode = intent.getBooleanExtra("launch", false)
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(q, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(30), dp(16), dp(16))
            setBackgroundColor(sand)
        }
        root.addView(label(if (launchMode) "Fast launch" else "Pinned apps", 22f, true))
        root.addView(TextView(this).apply {
            text = if (launchMode) "Tap an app to open it. ★ pins it." else "Tap an app to pin or unpin it."
            textSize = 13f; setTextColor(Color.DKGRAY)
        })
        search = EditText(this).apply {
            hint = "Search apps by name"; isSingleLine = true; textSize = 16f
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(sand2) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
        }
        root.addView(search)
        pinnedTitle = label("★ Pinned", 15f, true)
        pinnedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pinnedTitle); addView(pinnedBox); addView(label("All apps", 15f, true)); addView(listBox)
        }
        root.addView(ScrollView(this).apply { addView(body) })
        setContentView(root)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        render()
    }

    private fun tapApp(a: App) {
        if (launchMode) {
            val i = packageManager.getLaunchIntentForPackage(a.pkg)
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); finish() }
        } else togglePin(a)
    }

    private fun togglePin(a: App) {
        val p = pinned()
        if (!p.remove(a.pkg)) p.add(a.pkg)
        prefs.edit().putString("pinned", p.joinToString("\n")).apply()
        render()
    }

    private fun icon(a: App, size: Int) = ImageView(this).apply {
        setImageDrawable(a.icon); layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun cell(a: App): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(8), dp(2), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(icon(a, 48))
        addView(TextView(this@AppsActivity).apply {
            text = a.label; textSize = 11f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        setOnClickListener { tapApp(a) }
    }

    private fun rowView(a: App, isPinned: Boolean): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        addView(icon(a, 38))
        addView(TextView(this@AppsActivity).apply {
            text = a.label; textSize = 16f; setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) }
        })
        addView(TextView(this@AppsActivity).apply {
            text = if (isPinned) "★" else "☆"; textSize = 24f; setTextColor(rust)
            setPadding(dp(14), dp(4), dp(6), dp(4))
            setOnClickListener { togglePin(a) }
        })
        setOnClickListener { tapApp(a) }
    }

    private fun render() {
        val q = search.text.toString().trim().lowercase()
        val pins = pinned()
        val match = { a: App -> q.isEmpty() || a.label.lowercase().contains(q) }
        val pinApps = pins.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.filter(match)
        pinnedTitle.visibility = if (pinApps.isEmpty()) View.GONE else View.VISIBLE
        pinnedBox.removeAllViews()
        for (chunk in pinApps.chunked(4)) {
            val row = LinearLayout(this).apply { weightSum = 4f }
            for (a in chunk) row.addView(cell(a))
            pinnedBox.addView(row)
        }
        listBox.removeAllViews()
        for (a in apps.filter(match)) listBox.addView(rowView(a, pins.contains(a.pkg)))
    }
}
