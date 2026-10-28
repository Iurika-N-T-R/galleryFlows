package com.iurika.galleryflows

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** A folder holding media directly, as the destination list shows it. */
private class Place(val dir: File, val count: Int, val cover: File?)

private var lastWalk: Pair<Boolean, List<Place>>? = null // shown at once next time, then walked again

/** Every folder under the roots that holds media itself; [hidden] adds dot-folders and .nomedia ones. */
private fun places(hidden: Boolean): List<Place> {
    val out = ArrayList<Place>()
    fun walk(d: File, depth: Int) {
        val files = d.listFiles() ?: return
        if (!hidden && files.any { it.name == ".nomedia" }) return
        var count = 0
        var cover: File? = null
        for (f in files) when {
            f.isDirectory -> if (depth < 32 && f.name != Trash.DIR && (hidden || (!f.name.startsWith('.') && f.path !in Prefs.excluded))) walk(f, depth + 1)
            kindOf(f.name) != null && (hidden || !f.name.startsWith('.')) -> {
                count++
                if (cover == null || f.lastModified() > cover.lastModified()) cover = f
            }
        }
        if (count > 0) out += Place(d, count, cover)
    }
    Prefs.roots.map(::File).forEach { walk(it, 0) }
    // the recent destinations first, then the pinned ones, then by name
    val recent = Prefs.targets.withIndex().associate { it.value to it.index }
    return out.distinctBy { it.dir.path }.sortedWith(compareBy<Place>({ recent[it.dir.path] ?: Int.MAX_VALUE }, { it.dir.path !in Prefs.pinned })
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.dir.name })
}

/**
 * "Sélectionner la destination": every folder with media as a card with its cover, a search field, a tap picks it.
 * "Autre dossier" falls back to the folder browser for anything else (an empty folder, a new one).
 */
fun Activity.pickDestination(move: Boolean, onPick: (File) -> Unit) {
    val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
    var hidden = false
    var all: List<Place> = emptyList()
    val pick = { f: File -> Prefs.targets = listOf(f.path) + Prefs.targets; dialog.dismiss(); onPick(f) }

    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(88)) }
    val scroll = ScrollView(this).apply { addView(grid); isVerticalScrollBarEnabled = false }
    val status = TextView(this).apply { setTextColor(GREY); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0) }
    val search = EditText(this).apply {
        hint = t("Rechercher des dossiers"); setSingleLine(); background = null; textSize = 16f
        setTextColor(WHITE); setHintTextColor(blend(ACCENT, WHITE, .35f))
    }

    fun card(p: Place) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(12))
        addView(FrameLayout(this@pickDestination).apply {
            foreground = ripple(rounded(WHITE, 16))
            val img = SquareImageView(this@pickDestination).apply { background = rounded(0xff2a2a2a.toInt(), 16); clipToOutline = true }
            addView(img)
            Thumbs.load(img, p.cover)
            if (p.dir.path in Prefs.pinned) addView(ImageView(this@pickDestination).apply {
                setImageResource(R.drawable.ic_pin); background = rounded(0x66000000, 16); setPadding(dp(7), dp(7), dp(7), dp(7))
            }, FrameLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(8), dp(8), 0, 0) })
        })
        addView(TextView(this@pickDestination).apply {
            text = "${p.dir.name} (${p.count})"; setTextColor(WHITE); textSize = 15f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        })
        setOnClickListener { pick(p.dir) }
    }

    fun render() {
        val q = search.text.toString().trim()
        val shown = if (q.isEmpty()) all else all.filter { it.dir.name.contains(q, ignoreCase = true) }
        grid.removeAllViews()
        if (shown.isEmpty()) { status.text = t(if (all.isEmpty()) "Chargement…" else "Aucun dossier"); grid.addView(status); return }
        shown.chunked(2).forEach { row ->
            grid.addView(LinearLayout(this).apply {
                repeat(2) { i -> addView(row.getOrNull(i)?.let(::card) ?: View(this@pickDestination), LP(0, -2, 1f)) }
            })
        }
    }

    fun load() {
        val h = hidden
        lastWalk?.takeIf { it.first == h }?.let { all = it.second; render() } ?: run { all = emptyList(); render() }
        thread {
            val found = places(h)
            runOnUiThread {
                if (hidden != h || !dialog.isShowing) return@runOnUiThread
                lastWalk = h to found
                all = found; render()
            }
        }
    }

    search.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = render()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    fun textButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = t(label); setTextColor(ACCENT); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(16), dp(12), dp(16), dp(12)); background = ripple(rounded(WHITE, 20)); setOnClickListener { onClick() }
    }
    // the eye: hidden folders too
    val eye = ImageView(this).apply {
        setImageResource(R.drawable.ic_eye); scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = rounded(ACCENT, 18); foreground = ripple(rounded(WHITE, 18)); elevation = dp(6).toFloat()
        setOnClickListener { hidden = !hidden; setImageResource(if (hidden) R.drawable.ic_hide else R.drawable.ic_eye); load() }
    }

    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@pickDestination).apply {
            text = t("Sélectionner la destination"); setTextColor(WHITE); textSize = 22f; setPadding(dp(24), dp(24), dp(24), dp(16))
        })
        addView(LinearLayout(this@pickDestination).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rounded(BAR, 28); setPadding(dp(16), 0, dp(12), 0)
            addView(ImageView(this@pickDestination).apply { setImageResource(R.drawable.ic_search); setColorFilter(WHITE) }, LP(dp(22), dp(22)))
            addView(search, LP(0, -2, 1f).apply { marginStart = dp(14) })
        }, LP(-1, dp(56)).apply { setMargins(dp(16), 0, dp(16), dp(10)) })
        addView(FrameLayout(this@pickDestination).apply {
            addView(scroll)
            addView(eye, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(18), dp(12)) })
        }, LP(-1, 0, 1f))
        addView(LinearLayout(this@pickDestination).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(12))
            addView(textButton("Autre dossier") { dialog.dismiss(); pickFolder(t(if (move) "Déplacer vers" else "Copier vers"), null, pick) })
            addView(View(this@pickDestination), LP(0, 1, 1f))
            addView(textButton("Annuler") { dialog.dismiss() })
        })
    }
    sheet.setPadding(dp(12), dp(16), dp(12), dp(12)) // the card's inset, below
    dialog.setContentView(sheet)
    dialog.window?.apply {
        // a rounded card over the dimmed viewer or grid
        setBackgroundDrawable(InsetDrawable(rounded(SHEET, 28), dp(12), dp(16), dp(12), dp(12)))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.6f)
    }
    dialog.show()
    load()
}
