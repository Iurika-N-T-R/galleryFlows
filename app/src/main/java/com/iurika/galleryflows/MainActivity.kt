package com.iurika.galleryflows

import android.app.ActivityOptions
import android.app.AlertDialog
import android.widget.RadioGroup
import android.widget.RadioButton
import android.text.InputType
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.graphics.RectF
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Base() {
    private enum class Mode { BROWSE, FAVORITES, TRASH }

    private class Card(val name: String, val info: Info, val pinned: Boolean, val open: () -> Unit, val options: (() -> Unit)?)

    private var dir: File? = null      // null = the roots screen (in BROWSE mode)
    private var mode = Mode.BROWSE
    private var rows: List<List<Any>> = emptyList() // each row holds Cards (folders) or Files (media)
    private var media: List<File> = emptyList()
    private val selected = LinkedHashSet<File>()
    private val scrollMemory = HashMap<String, Int>()
    private var generation = 0
    private var slideFrom = 0 // next list shown slides in from this side: 1 = deeper, -1 = back up, 0 = no animation
    private var leaving = false // the old level is still sliding out
    private var whenLeft: (() -> Unit)? = null
    private val standard = PathInterpolator(.2f, 0f, 0f, 1f) // Material standard easing
    private var built = false
    private val handler = Handler(Looper.getMainLooper())
    private val searchLater = Runnable { load() }

    // Another app asked us for a picture (ACTION_GET_CONTENT / ACTION_PICK)
    private val picking by lazy { intent.action == Intent.ACTION_GET_CONTENT || intent.action == Intent.ACTION_PICK }
    private val pickMultiple by lazy { intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) }
    private val pickType by lazy { intent.type?.takeIf { it != "*/*" } }

    private lateinit var back: ImageView
    private lateinit var search: EditText
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var title: TextView
    private lateinit var shuffleButton: ImageView
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("path")?.let { dir = File(it) } // opened from a folder shortcut
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("path")?.let { if (built) go(File(it)) else dir = File(it) }
    }

    override fun onUnlocked() {
        if (!Environment.isExternalStorageManager()) return askPermission()
        if (!built) { build(); built = true }
        if (Session.dirty) { Session.dirty = false; Scan.clear() }
        load(keepScroll = true)
        if (Scan.stale) {
            Scan.stale = false
            thread { Scan.rescan(); runOnUiThread { if (!isDestroyed) load(keepScroll = true) } }
        }
    }

    override fun onStop() {
        super.onStop()
        thread { Scan.save() } // next launch starts from this walk
    }

    private fun askPermission() {
        built = false
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            addView(TextView(this@MainActivity).apply {
                text = "galleryFlows a besoin de l'accès à tous les fichiers pour parcourir tes dossiers et sous-dossiers."
                textSize = 16f; gravity = Gravity.CENTER; setTextColor(WHITE)
            })
            addView(Button(this@MainActivity).apply {
                text = "Autoriser"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
            }, LP(-2, -2).apply { topMargin = dp(16) })
        })
    }

    private fun build() {
        back = icon(R.drawable.ic_search) { if (dir != null || mode != Mode.BROWSE) onBackPressed() else focusSearch() }
        search = EditText(this).apply {
            setSingleLine(); background = null; textSize = 17f
            setTextColor(WHITE); setHintTextColor(0xffc8b49c.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { handler.removeCallbacks(searchLater); handler.postDelayed(searchLater, 350) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rounded(BAR, 28); setPadding(dp(4), 0, dp(4), 0)
            addView(back, LP(dp(48), dp(48)))
            addView(search, LP(0, -2, 1f))
            addView(icon(R.drawable.ic_more) { menu(it) }, LP(dp(48), dp(48)))
        }
        crumbs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        crumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(crumbs) }
        title = TextView(this).apply {
            setTextColor(WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        }
        shuffleButton = icon(R.drawable.ic_shuffle) { shuffleDialog() }.apply { contentDescription = "Aléatoire" }
        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LP(0, -2, 1f))
            addView(shuffleButton, LP(dp(48), dp(48)))
        }
        status = TextView(this).apply { setTextColor(GREY); textSize = 13f; setPadding(dp(20), 0, dp(20), dp(10)) }
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null; dividerHeight = 0; selector = ColorDrawable(0); isFastScrollEnabled = true
        }
        selectionCount = TextView(this).apply { setTextColor(WHITE); textSize = 17f }
        selectionBar = LinearLayout(this).apply { visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(BAR) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LP(-1, dp(56)).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) })
            addView(crumbScroll, LP(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), 0) })
            addView(titleRow, LP(-1, -2).apply { setMargins(dp(20), dp(4), dp(8), 0) })
            addView(status)
            addView(list, LP(-1, 0, 1f))
            addView(selectionBar, LP(-1, -2))
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom); ins
            }
        }
        setContentView(root)
        Session.locate = ::locate
    }

    override fun onDestroy() {
        super.onDestroy()
        Session.locate = null
    }

    private fun focusSearch() {
        search.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
    }

    // ---- navigation ----

    private fun key() = when (mode) { Mode.FAVORITES -> "★"; Mode.TRASH -> "🗑"; else -> dir?.path ?: "" }

    /** The added root that [d] lives under (the deepest one if roots are nested). */
    private fun rootOf(d: File) = Prefs.roots.filter { d.path == it || d.path.startsWith("$it/") }.maxByOrNull { it.length }?.let(::File) ?: d

    private fun go(f: File?, to: Mode = Mode.BROWSE, back: Boolean = false) {
        scrollMemory[key()] = list.firstVisiblePosition
        dir = f; mode = to
        slideFrom = if (back) -1 else 1
        leave()
        selected.clear()
        search.text.clear(); handler.removeCallbacks(searchLater)
        load()
    }

    // Material "shared axis X": the old level slides 30 dp away while fading out, the new one comes in from the other side
    private fun leave() {
        leaving = true
        list.animate().cancel()
        list.animate().alpha(0f).translationX(-dp(30) * slideFrom.toFloat()).setDuration(90).setInterpolator(standard).withEndAction {
            leaving = false
            whenLeft?.invoke(); whenLeft = null
        }.start()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val d = dir
        when {
            !built -> super.onBackPressed()
            selected.isNotEmpty() -> { selected.clear(); updateSelection() }
            search.text.isNotEmpty() -> search.text.clear()
            mode != Mode.BROWSE -> go(null, back = true)
            d != null -> go(if (d.path == rootOf(d).path) null else d.parentFile, back = true)
            else -> super.onBackPressed()
        }
    }

    private fun renderHeader() {
        val d = dir
        val inside = d != null || mode != Mode.BROWSE
        back.setImageResource(if (inside) R.drawable.ic_back else R.drawable.ic_search)
        search.hint = when {
            mode == Mode.FAVORITES -> "Rechercher dans Favoris"
            mode == Mode.TRASH -> "Rechercher dans la corbeille"
            d != null -> "Rechercher dans ${d.name}"
            picking -> "Choisir un fichier"
            else -> "Rechercher des fichiers"
        }
        // Big title for where we are; the way back up is a small path above it
        title.text = when { mode == Mode.FAVORITES -> "Favoris"; mode == Mode.TRASH -> "Corbeille"; d != null -> d.name; else -> "Galerie" }
        shuffleButton.visibility = if (mode == Mode.TRASH || picking) View.GONE else View.VISIBLE
        crumbs.removeAllViews()
        crumbScroll.visibility = if (inside) View.VISIBLE else View.GONE
        crumb("Galerie") { go(null, back = true) }
        if (d != null) {
            val root = rootOf(d)
            generateSequence(d.parentFile) { it.parentFile }.takeWhile { it.path.length >= root.path.length }
                .toList().asReversed().forEach { f -> crumb(f.name) { go(f, back = true) } }
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
        renderSelectionBar()
    }

    private fun crumb(label: String, onClick: () -> Unit) {
        if (crumbs.childCount > 0) crumbs.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron); setColorFilter(GREY)
        }, LP(dp(16), dp(16)))
        crumbs.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(GREY); setSingleLine(); setPadding(dp(8), dp(6), dp(8), dp(6))
            background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, rounded(WHITE, 14))
            setOnClickListener { onClick() }
        })
    }

    // ---- loading ----

    private fun load(keepScroll: Boolean = false) {
        val d = dir
        val m = mode
        val q = search.text.toString().trim()
        val gen = ++generation
        renderHeader()
        if (!keepScroll) status.text = "Chargement…"
        thread {
            var cards: List<Card> = emptyList()
            var files: List<File> = when {
                m == Mode.FAVORITES -> Scan.sortFiles(Prefs.favorites.map(::File).filter { it.exists() })
                m == Mode.TRASH -> Trash.items()
                q.isNotEmpty() -> Scan.search(listOfNotNull(d).ifEmpty { Prefs.roots.map(::File) }, q)
                d == null -> { cards = rootCards(); emptyList() }
                else -> {
                    val (dirs, found) = Scan.children(d)
                    // only folders with media somewhere below them, like a gallery, but at every level
                    cards = Scan.sortFolders(dirs.filter { Scan.info(it).count > 0 }).map { f ->
                        Card(f.name, Scan.info(f), f.path in Prefs.pinned, { go(f) }, { folderOptions(f) })
                    }
                    Scan.sortFiles(found)
                }
            }
            if (q.isNotEmpty() && m != Mode.BROWSE) files = files.filter { it.name.contains(q, true) }
            pickType?.let { t -> files = files.filter { mimeMatches(mimeOf(it), t) } }
            runOnUiThread { if (gen == generation) show(cards, files, keepScroll) }
        }
    }

    private fun mimeMatches(mime: String, pattern: String) =
        pattern.split(',').any { p -> p.trim().let { it == mime || (it.endsWith("/*") && mime.startsWith(it.dropLast(1))) } }

    private fun rootCards(): List<Card> {
        val favs = Prefs.favorites.map(::File).filter { it.exists() }
        val fav = if (favs.isEmpty()) emptyList()
        else listOf(Card("Favoris", Info(favs.size, favs.maxByOrNull { it.lastModified() }, 0), false, { go(null, Mode.FAVORITES) }, null))
        val roots = Prefs.roots.map(::File).sortedByDescending { it.path in Prefs.pinned }.map { f ->
            Card(f.name, Scan.info(f), f.path in Prefs.pinned, { go(f) }, { rootOptions(f) })
        }
        val trashed = if (Prefs.showTrash && !picking) Trash.items() else emptyList()
        val trash = if (trashed.isEmpty()) emptyList()
        else listOf(Card("Corbeille", Info(trashed.size, trashed.first(), 0), false, { go(null, Mode.TRASH) }, null))
        return fav + roots + trash
    }

    private fun show(cards: List<Card>, files: List<File>, keepScroll: Boolean) {
        if (leaving) { whenLeft = { show(cards, files, keepScroll) }; return }
        media = files
        selected.retainAll(files.toSet())
        rows = if (Prefs.listView) (cards + files).map { listOf(it) }
        else cards.chunked(Prefs.folderCols) + files.chunked(Prefs.mediaCols)
        adapter.notifyDataSetChanged()
        if (!keepScroll) list.setSelection(scrollMemory[key()] ?: 0)
        if (slideFrom != 0) {
            list.alpha = 0f; list.translationX = dp(30) * slideFrom.toFloat()
            list.animate().alpha(1f).translationX(0f).setDuration(210).setInterpolator(standard).start()
            slideFrom = 0
        }
        status.text = listOfNotNull(
            cards.size.takeIf { it > 0 }?.let { plural(it, "dossier") },
            files.size.takeIf { it > 0 }?.let { plural(it, "fichier") },
            if (mode == Mode.TRASH && files.isNotEmpty()) "supprimés définitivement après 30 jours" else null,
        ).joinToString(" · ").ifEmpty { if (mode == Mode.TRASH) "La corbeille est vide" else "Aucun média ici" }
        updateSelection()
    }

    private fun refresh() { Scan.clear(); load(keepScroll = true) }

    // ---- grid / list ----

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(p: Int) = when { Prefs.listView -> 2; rows[p][0] is Card -> 0; else -> 1 }

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = rows[p]
            if (Prefs.listView) {
                val v = (convert as? LinearLayout)?.takeIf { it.tag == "line" } ?: lineCell()
                when (val item = row[0]) {
                    is Card -> bindLine(v, item.info.cover, item.name, plural(item.info.count, "fichier"), item.pinned, false,
                        { item.open() }, { item.options?.invoke(); item.options != null })
                    is File -> bindLine(v, item, item.name, details(item), false, item in selected, { tap(item, v.getChildAt(0)) }, { toggle(item); true })
                }
                return v
            }
            val folder = row[0] is Card
            val cols = if (folder) Prefs.folderCols else Prefs.mediaCols
            val v = (convert as? LinearLayout)?.takeIf { it.childCount == cols && it.tag == null } ?: LinearLayout(this@MainActivity).apply {
                if (folder) setPadding(dp(6), 0, dp(6), 0)
                repeat(cols) { addView(if (folder) folderCell() else mediaCell(), LP(0, -2, 1f)) }
            }
            for (i in 0 until cols) {
                val cell = v.getChildAt(i)
                when (val item = row.getOrNull(i)) {
                    is Card -> bindFolder(cell as LinearLayout, item)
                    is File -> bindMedia(cell as FrameLayout, item)
                }
                cell.visibility = if (i < row.size) View.VISIBLE else View.INVISIBLE
            }
            return v
        }
    }

    private fun details(f: File): String {
        val ago = DateUtils.getRelativeTimeSpanString(f.lastModified())
        val origin = if (mode == Mode.TRASH) Trash.originalOf(f)?.let { " · ${File(it).parent}" } ?: "" else ""
        return "${Formatter.formatShortFileSize(this, f.length())} · $ago$origin"
    }

    private fun lineCell() = LinearLayout(this).apply {
        tag = "line"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6))
        addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(0xff2a2a2a.toInt(), 8); clipToOutline = true }, LP(dp(64), dp(64)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { setTextColor(WHITE); textSize = 16f; setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE })
            addView(TextView(this@MainActivity).apply { setTextColor(GREY); textSize = 13f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        }, LP(0, -2, 1f).apply { marginStart = dp(14) })
        addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_pin); setColorFilter(GREY) }, LP(dp(20), dp(20)))
    }

    private fun bindLine(v: LinearLayout, thumb: File?, title: String, sub: String, pinned: Boolean, isSelected: Boolean, click: () -> Unit, long: () -> Boolean) {
        Thumbs.load(v.getChildAt(0) as ImageView, thumb)
        val texts = v.getChildAt(1) as LinearLayout
        (texts.getChildAt(0) as TextView).text = title
        (texts.getChildAt(1) as TextView).text = sub
        v.getChildAt(2).visibility = if (pinned) View.VISIBLE else View.GONE
        v.background = if (isSelected) ColorDrawable(0x66f57c00) else ripple()
        v.setOnClickListener { click() }
        v.setOnLongClickListener { long() }
    }

    private fun folderCell() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(12))
        addView(FrameLayout(this@MainActivity).apply {
            foreground = ripple(rounded(WHITE, 16))
            addView(SquareImageView(this@MainActivity).apply { clipToOutline = true })
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_pin); background = rounded(0x66000000, 16); setPadding(dp(6), dp(6), dp(6), dp(6))
            }, FrameLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(8), dp(8), 0, 0) })
        })
        addView(TextView(this@MainActivity).apply {
            textSize = 15f; setTextColor(WHITE); setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
        })
    }

    private fun bindFolder(cell: LinearLayout, card: Card) {
        val frame = cell.getChildAt(0) as FrameLayout
        val img = frame.getChildAt(0) as SquareImageView
        img.background = rounded(0xff2a2a2a.toInt(), if (Prefs.roundFolders) 16 else 0)
        img.scaleType = if (Prefs.cropThumbs) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        Thumbs.load(img, card.info.cover)
        frame.getChildAt(1).visibility = if (card.pinned) View.VISIBLE else View.GONE
        (cell.getChildAt(1) as TextView).text = "${card.name} (${card.info.count})"
        cell.setOnClickListener { card.open() }
        cell.setOnLongClickListener { card.options?.invoke(); card.options != null }
    }

    private fun mediaCell() = FrameLayout(this).apply {
        setPadding(dp(1), dp(1), dp(1), dp(1))
        foreground = ripple()
        addView(SquareImageView(this@MainActivity))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_play); background = rounded(0x66000000, 18); setPadding(dp(6), dp(6), dp(6), dp(6))
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        addView(View(this@MainActivity).apply { setBackgroundColor(0x88f57c00.toInt()); visibility = View.GONE }, FrameLayout.LayoutParams(-1, -1)) // selected
    }

    private fun bindMedia(cell: FrameLayout, f: File) {
        val img = cell.getChildAt(0) as SquareImageView
        img.scaleType = if (Prefs.cropThumbs) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        Thumbs.load(img, f)
        cell.getChildAt(1).visibility = if (kindOf(f.name) == Kind.VIDEO) View.VISIBLE else View.GONE
        val sel = cell.getChildAt(2)
        val on = f in selected
        if (on != (sel.visibility == View.VISIBLE)) {
            sel.visibility = if (on) View.VISIBLE else View.GONE
            // the picture shrinks a little while selected
            img.animate().scaleX(if (on) .88f else 1f).scaleY(if (on) .88f else 1f).setDuration(150).start()
        }
        cell.setOnClickListener { tap(f, img) }
        cell.setOnLongClickListener { toggle(f); true }
    }

    private fun tap(f: File, from: View) {
        when {
            selected.isNotEmpty() -> toggle(f)
            picking && !pickMultiple -> returnPicked(listOf(f))
            else -> {
                Session.items = media
                // the viewer grows the picture out of this thumbnail itself, so no window animation
                startActivity(Intent(this, ViewerActivity::class.java).putExtra("pos", media.indexOf(f)).putExtra("trash", mode == Mode.TRASH)
                    .putExtra("from", rectOf(from)), ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
            }
        }
    }

    private fun rectOf(v: View): RectF {
        val p = IntArray(2).also(v::getLocationInWindow)
        return RectF(p[0].toFloat(), p[1].toFloat(), p[0] + v.width.toFloat(), p[1] + v.height.toFloat())
    }

    /** The thumbnail showing [f] on screen right now, if any (Thumbs tags each view with "path|…"). */
    private fun locate(f: File): RectF? {
        val prefix = "${f.path}|"
        fun find(v: View): View? = when {
            v is ImageView && (v.tag as? String)?.startsWith(prefix) == true && v.isShown -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(list)?.let(::rectOf)
    }

    // ---- shuffle ----

    /** Random order over this folder (with its subfolders or not), moving on by itself after a timer. */
    private fun shuffleDialog() {
        val d = dir
        val favorites = mode == Mode.FAVORITES
        val pad = dp(24)
        val only = RadioButton(this).apply { id = View.generateViewId(); text = if (d == null) "Dossiers principaux seulement" else "Ce dossier seulement" }
        val deep = RadioButton(this).apply { id = View.generateViewId(); text = "Avec tous les sous-dossiers" }
        val scope = RadioGroup(this).apply { addView(only); addView(deep); check(if (Prefs.shuffleSub) deep.id else only.id) }
        val minutes = Prefs.slideSeconds >= 60 && Prefs.slideSeconds % 60 == 0
        val amount = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText("${if (minutes) Prefs.slideSeconds / 60 else Prefs.slideSeconds}")
            gravity = Gravity.CENTER; setSelectAllOnFocus(true)
        }
        val sec = RadioButton(this).apply { id = View.generateViewId(); text = "secondes" }
        val min = RadioButton(this).apply { id = View.generateViewId(); text = "minutes" }
        val unit = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; addView(sec); addView(min); check(if (minutes) min.id else sec.id) }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0)
            if (!favorites) addView(scope)
            addView(TextView(this@MainActivity).apply { text = "Passer au suivant toutes les"; setPadding(0, dp(16), 0, 0) })
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(amount, LP(dp(72), -2).apply { marginEnd = dp(12) })
                addView(unit)
            })
        }
        AlertDialog.Builder(this).setTitle("Aléatoire").setView(view)
            .setPositiveButton("Lancer") { _, _ ->
                val n = amount.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                Prefs.slideSeconds = if (unit.checkedRadioButtonId == min.id) n * 60 else n
                Prefs.shuffleSub = scope.checkedRadioButtonId == deep.id
                val sources = if (d != null) listOf(d) else Prefs.roots.map(::File)
                val sub = Prefs.shuffleSub
                thread {
                    val files = when {
                        favorites -> media
                        sub -> Scan.collect(sources)
                        else -> sources.flatMap { Scan.children(it).second }
                    }.shuffled()
                    runOnUiThread {
                        if (files.isEmpty()) return@runOnUiThread toast("Aucun média ici")
                        Session.items = files
                        startActivity(Intent(this, ViewerActivity::class.java).putExtra("pos", 0).putExtra("shuffle", true),
                            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
                    }
                }
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun returnPicked(files: List<File>) {
        val uris = files.map(Files::uri)
        val result = Intent().setData(uris[0]).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        setResult(RESULT_OK, result)
        finish()
    }

    // ---- selection & file actions ----

    private fun toggle(f: File) { if (!selected.add(f)) selected.remove(f); updateSelection() }

    private fun updateSelection() {
        selectionBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selectionCount.text = "${selected.size}"
        adapter.notifyDataSetChanged()
    }

    private fun renderSelectionBar() {
        selectionBar.removeAllViews()
        selectionBar.addView(icon(R.drawable.ic_close) { selected.clear(); updateSelection() })
        selectionBar.addView(selectionCount, LP(0, -2, 1f))
        selectionBar.addView(TextView(this).apply {
            text = "Tout"; setTextColor(WHITE); textSize = 15f; setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { selected.addAll(media); updateSelection() }
        })
        when {
            picking -> selectionBar.addView(icon(R.drawable.ic_check) { returnPicked(selected.toList()) })
            mode == Mode.TRASH -> {
                selectionBar.addView(icon(R.drawable.ic_restore) { val files = selected.toList(); runOp { Trash.restore(this, files) } })
                selectionBar.addView(icon(R.drawable.ic_delete) { deleteSelected() })
            }
            else -> {
                selectionBar.addView(icon(R.drawable.ic_share) { share(selected) })
                selectionBar.addView(icon(R.drawable.ic_copy) { transfer(move = false) })
                selectionBar.addView(icon(R.drawable.ic_folder) { transfer(move = true) })
                selectionBar.addView(icon(R.drawable.ic_delete) { deleteSelected() })
            }
        }
    }

    private fun runOp(work: () -> String) {
        thread {
            val msg = work()
            runOnUiThread { toast(msg); selected.clear(); refresh() }
        }
    }

    private fun transfer(move: Boolean) {
        val files = selected.toList()
        pickFolder(if (move) "Déplacer vers" else "Copier vers", dir) { dest ->
            guardOps { toast(if (move) "Déplacement…" else "Copie…"); runOp { Ops.transfer(this, files, dest, move) } }
        }
    }

    private fun deleteSelected() {
        val files = selected.toList()
        val permanent = mode == Mode.TRASH || !Prefs.useTrash
        val run = { guardOps { runOp { Ops.delete(this, files, permanent) } } }
        if (Prefs.skipDeleteConfirm) run()
        else confirm(if (permanent) "Supprimer définitivement ${plural(files.size, "fichier")} ?" else "Mettre ${plural(files.size, "fichier")} à la corbeille ?", run)
    }

    private fun folderOptions(f: File) = choose(f.name, listOfNotNull(
        (if (f.path in Prefs.pinned) "Désépingler" else "Épingler en haut") to { togglePin(f) },
        "Renommer" to { ask("Renommer", f.name) { n -> runOp { if (Ops.rename(this, f, n) != null) "Renommé" else "Impossible de renommer" } } },
        (if (Scan.hasNomedia(f)) "Afficher (retirer .nomedia)" else "Cacher (ajouter .nomedia)") to { Ops.setHidden(f, !Scan.hasNomedia(f)); refresh() },
        "Exclure de la galerie" to { Prefs.excluded = Prefs.excluded + f.path; refresh() },
        "Ajouter à l'écran principal" to { Prefs.roots = Prefs.roots + f.path; toast("Ajouté à l'écran principal") },
        "Créer un raccourci" to { shortcut(f) },
    ))

    private fun rootOptions(f: File) = choose(f.path, listOf(
        (if (f.path in Prefs.pinned) "Désépingler" else "Épingler en haut") to { togglePin(f) },
        "Créer un raccourci" to { shortcut(f) },
        "Retirer de l'écran principal" to { Prefs.roots = Prefs.roots - f.path; load(keepScroll = true) },
    ))

    private fun togglePin(f: File) {
        Prefs.pinned = if (f.path in Prefs.pinned) Prefs.pinned - f.path else Prefs.pinned + f.path
        load(keepScroll = true)
    }

    private fun shortcut(f: File) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) return toast("Le lanceur ne gère pas les raccourcis")
        val cover = Scan.info(f).cover?.let { Thumbs.cached(it) }
        val info = ShortcutInfo.Builder(this, f.path).setShortLabel(f.name)
            .setIcon(cover?.let { Icon.createWithBitmap(it) } ?: Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("path", f.path)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            .build()
        sm.requestPinShortcut(info, null)
    }

    // ---- menu ----

    private fun menu(anchor: View) {
        val d = dir
        val actions = listOfNotNull<Pair<String, () -> Unit>>(
            "Trier par" to ::sortDialog,
            "Filtrer les médias" to ::filterDialog,
            "Mode d'affichage : ${if (Prefs.listView) "liste" else "grille"}" to { Prefs.listView = !Prefs.listView; load(keepScroll = true) },
            "Colonnes des dossiers" to { columns(Prefs.folderCols) { Prefs.folderCols = it } },
            "Colonnes des médias" to { columns(Prefs.mediaCols) { Prefs.mediaCols = it } },
            (if (Prefs.showHidden) "Masquer les fichiers cachés" else "Afficher les fichiers cachés") to ::toggleHidden,
            d?.takeIf { mode == Mode.BROWSE }?.let {
                "Créer un nouveau dossier" to {
                    ask("Nouveau dossier", "") { n -> toast(if (File(d, n).mkdir()) "Créé, visible dès qu'il contient des médias" else "Création impossible") }
                }
            },
            if (mode == Mode.TRASH) "Vider la corbeille" to {
                confirm("Vider la corbeille ? Les fichiers seront supprimés définitivement.") { guardOps { runOp { Trash.empty(this) } } }
            } else null,
            "Ajouter un dossier" to {
                pickFolder("Ajouter un dossier", d) { f -> Prefs.roots = Prefs.roots + f.path; go(null) }
            },
            if (mode != Mode.TRASH && !picking) "Aléatoire…" to ::shuffleDialog else null,
            "Actualiser" to ::refresh,
            if (picking) null else "Paramètres" to { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        PopupMenu(this, anchor).apply {
            actions.forEachIndexed { i, a -> menu.add(0, i, i, a.first) }
            setOnMenuItemClickListener { actions[it.itemId].second(); true }
            show()
        }
    }

    private fun toggleHidden() {
        val flip = { Prefs.showHidden = !Prefs.showHidden; refresh() }
        if (!Prefs.showHidden && Prefs.lockHidden) authenticate("Afficher les éléments cachés", flip) else flip()
    }

    private fun sortDialog() {
        var choice = Prefs.sort
        fun apply(desc: Boolean) { Prefs.sort = choice; Prefs.desc = desc; load(keepScroll = true) }
        AlertDialog.Builder(this).setTitle("Trier par")
            .setSingleChoiceItems(arrayOf("Nom", "Date de modification", "Taille", "Aléatoire", "Date de prise de vue"), choice) { _, i -> choice = i }
            .setPositiveButton("Décroissant") { _, _ -> apply(true) }
            .setNegativeButton("Croissant") { _, _ -> apply(false) }
            .show()
    }

    private fun filterDialog() {
        val checked = BooleanArray(Kind.entries.size) { Prefs.kinds and (1 shl it) != 0 }
        AlertDialog.Builder(this).setTitle("Filtrer les médias")
            .setMultiChoiceItems(arrayOf("Images", "Vidéos", "GIF"), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("OK") { _, _ -> Prefs.kinds = checked.indices.filter { checked[it] }.sumOf { 1 shl it }; refresh() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun columns(current: Int, set: (Int) -> Unit) {
        val labels = (1..10).map { if (it == 1) "1 colonne" else "$it colonnes" }.toTypedArray()
        AlertDialog.Builder(this).setSingleChoiceItems(labels, current - 1) { dialog, i ->
            set(i + 1); dialog.dismiss(); load(keepScroll = true)
        }.show()
    }
}

fun plural(n: Int, word: String) = "$n $word" + if (n > 1) "s" else ""
