package com.iurika.galleryflows

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * Where to copy or move: the recent destinations, the galleries and the bookmarked folders as cards with their cover.
 * A tap goes into a folder, the button at the bottom picks the one shown.
 */
fun Activity.pickDestination(move: Boolean, onPick: (File) -> Unit) {
    val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)
    val stack = ArrayList<File>() // folders gone into; empty = the overview
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(16)) }
    val scroll = ScrollView(this).apply { addView(body) }
    val title = TextView(this).apply { setTextColor(WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setSingleLine(); ellipsize = TextUtils.TruncateAt.START }
    val button = TextView(this).apply {
        setTextColor(WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE
        background = rounded(ACCENT, 28); foreground = ripple(rounded(WHITE, 28)); setPadding(dp(20), 0, dp(20), 0)
    }
    lateinit var render: () -> Unit
    val back = { if (stack.isEmpty()) dialog.dismiss() else { stack.removeAt(stack.lastIndex); render() } }

    fun card(f: File, info: Info) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(10))
        addView(FrameLayout(this@pickDestination).apply {
            foreground = ripple(rounded(WHITE, 14))
            val img = SquareImageView(this@pickDestination).apply { background = rounded(0xff2a2a2a.toInt(), 14); clipToOutline = true }
            addView(img)
            if (info.cover != null) Thumbs.load(img, info.cover)
            else addView(ImageView(this@pickDestination).apply { setImageResource(R.drawable.ic_folder); setColorFilter(GREY) },
                FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        })
        addView(TextView(this@pickDestination).apply {
            text = f.name; setTextColor(WHITE); textSize = 14f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
        })
        addView(TextView(this@pickDestination).apply { text = plural(info.count, "fichier"); setTextColor(GREY); textSize = 12f })
        setOnClickListener { stack += f; render() }
    }

    fun section(label: String?, folders: List<Pair<File, Info>>) {
        if (label != null) body.addView(TextView(this).apply {
            text = t(label).uppercase(); setTextColor(GREY); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = .08f
            setPadding(dp(8), dp(18), dp(8), dp(4))
        })
        folders.chunked(3).forEach { row ->
            body.addView(LinearLayout(this).apply {
                repeat(3) { i -> addView(row.getOrNull(i)?.let { (f, info) -> card(f, info) } ?: View(this@pickDestination), LP(0, -2, 1f)) }
            })
        }
    }

    render = {
        val here = stack.lastOrNull()
        title.text = here?.name ?: t(if (move) "Déplacer vers" else "Copier vers")
        button.visibility = if (here == null) View.GONE else View.VISIBLE
        here?.let { button.text = t(if (move) "Déplacer dans « %s »" else "Copier dans « %s »", it.name) }
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        thread {
            fun withInfo(dirs: List<File>) = dirs.filter { it.isDirectory }.distinct().map { it to Scan.info(it) }
            val sections = if (here == null) listOf(
                "Récents" to withInfo(Prefs.targets.map(::File)),
                "Galeries" to withInfo(Prefs.roots.map(::File)),
                "Bookmarks" to withInfo(Prefs.bookmarks.values.flatten().map(::File)),
            ).filter { it.second.isNotEmpty() }
            else listOf(null to withInfo(Scan.children(here).first.sortedBy { it.name.lowercase() }))
            runOnUiThread {
                if (stack.lastOrNull() != here || !dialog.isShowing) return@runOnUiThread
                sections.forEach { (label, folders) -> section(label, folders) }
                if (here != null && sections.single().second.isEmpty()) body.addView(TextView(this).apply {
                    text = t("Pas de sous-dossier ici"); setTextColor(GREY); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0)
                })
            }
        }
    }

    button.setOnClickListener {
        val dest = stack.last()
        Prefs.targets = listOf(dest.path) + Prefs.targets
        dialog.dismiss()
        onPick(dest)
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@pickDestination).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(16), dp(4))
            addView(icon(R.drawable.ic_back) { back() }, LP(dp(48), dp(48)))
            addView(title, LP(0, -2, 1f).apply { marginStart = dp(4) })
        })
        addView(scroll, LP(-1, 0, 1f))
        addView(button, LP(-1, dp(56)).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) })
        setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }
    }
    dialog.setContentView(root)
    dialog.window?.setBackgroundDrawable(ColorDrawable(BG))
    dialog.setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { back(); true } else code == KeyEvent.KEYCODE_BACK }
    dialog.show()
    render()
}
