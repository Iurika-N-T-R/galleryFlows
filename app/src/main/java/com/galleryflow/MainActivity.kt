package com.galleryflow

import android.app.AlertDialog
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
    }

    private fun askPermission() {
        built = false
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            addView(TextView(this@MainActivity).apply {
                text = "Gallery Flow a besoin de l'accès à tous les fichiers pour parcourir tes dossiers et sous-dossiers."
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
        status = TextView(this).apply { setTextColor(GREY); textSize = 12f; setPadding(dp(16), dp(2), dp(16), dp(6)) }
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null; dividerHeight = 0; selector = ColorDrawable(0); isFastScrollEnabled = true
        }
        selectionCount = TextView(this).apply { setTextColor(WHITE); textSize = 17f }
        selectionBar = LinearLayout(this).apply { visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(BAR) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LP(-1, dp(56)).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) })
            addView(crumbScroll, LP(-1, -2).apply { marginStart = dp(8) })
            addView(status)
            addView(list, LP(-1, 0, 1f))
            addView(selectionBar, LP(-1, -2))
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom); ins
            }
        }
        setContentView(root)
    }

    private fun focusSearch() {
        search.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
    }

    // ---- navigation ----

    private fun key() = when (mode) { Mode.FAVORITES -> "★"; Mode.TRASH -> "🗑"; else -> dir?.path ?: "" }

    /** The added root that [d] lives under (the deepest one if roots are nested). */
    private fun rootOf(d: File) = Prefs.roots.filter { d.path == it || d.path.startsWith("$it/") }.maxByOrNull { it.length }?.let(::File) ?: d

    private fun go(f: File?, to: Mode = Mode.BROWSE) {
        scrollMemory[key()] = list.firstVisiblePosition
        dir = f; mode = to
        selected.clear()
        search.text.clear(); handler.removeCallbacks(searchLater)
        load()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val d = dir
        when {
            !built -> super.onBackPressed()
            selected.isNotEmpty() -> { selected.clear(); updateSelection() }
            search.text.isNotEmpty() -> search.text.clear()
            mode != Mode.BROWSE -> go(null)
            d != null -> go(if (d.path == rootOf(d).path) null else d.parentFile)
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
        crumbs.removeAllViews()
        crumbScroll.visibility = if (inside) View.VISIBLE else View.GONE
        crumb("Galerie") { go(null) }
        if (mode == Mode.FAVORITES) crumb("Favoris") {}
        if (mode == Mode.TRASH) crumb("Corbeille") {}
        if (d != null) {
            val root = rootOf(d)
            generateSequence(d) { it.parentFile }.takeWhile { it.path.length >= root.path.length }
                .toList().asReversed().forEach { f -> crumb(f.name) { go(f) } }
        }
        (crumbs.getChildAt(crumbs.childCount - 1) as TextView).setTextColor(ACCENT)
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
        renderSelectionBar()
    }

    private fun crumb(label: String, onClick: () -> Unit) {
        if (crumbs.childCount > 0) crumbs.addView(TextView(this).apply { text = "›"; setTextColor(GREY); textSize = 18f })
        crumbs.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
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
        media = files
        selected.retainAll(files.toSet())
        rows = if (Prefs.listView) (cards + files).map { listOf(it) }
        else cards.chunked(Prefs.folderCols) + files.chunked(Prefs.mediaCols)
        adapter.notifyDataSetChanged()
        if (!keepScroll) list.setSelection(scrollMemory[key()] ?: 0)
        status.text = listOfNotNull(
            cards.size.takeIf { it > 0 }?.let { "$it dossier(s)" },
            files.size.takeIf { it > 0 }?.let { "$it fichier(s)" },
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
                    is Card -> bindLine(v, item.info.cover, item.name, "${item.info.count} fichier(s)", item.pinned, false,
                        { item.open() }, { item.options?.invoke(); item.options != null })
                    is File -> bindLine(v, item, item.name, details(item), false, item in selected, { tap(item) }, { toggle(item); true })
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
        v.background = if (isSelected) ColorDrawable(0x66f57c00) else null
        v.setOnClickListener { click() }
        v.setOnLongClickListener { long() }
    }

    private fun folderCell() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(12))
        addView(FrameLayout(this@MainActivity).apply {
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
        addView(SquareImageView(this@MainActivity))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_play); background = rounded(0x66000000, 18); setPadding(dp(6), dp(6), dp(6), dp(6))
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
    }

    private fun bindMedia(cell: FrameLayout, f: File) {
        val img = cell.getChildAt(0) as SquareImageView
        img.scaleType = if (Prefs.cropThumbs) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        Thumbs.load(img, f)
        cell.getChildAt(1).visibility = if (kindOf(f.name) == Kind.VIDEO) View.VISIBLE else View.GONE
        cell.foreground = if (f in selected) ColorDrawable(0x88f57c00.toInt()) else null
        cell.setOnClickListener { tap(f) }
        cell.setOnLongClickListener { toggle(f); true }
    }

    private fun tap(f: File) {
        when {
            selected.isNotEmpty() -> toggle(f)
            picking && !pickMultiple -> returnPicked(listOf(f))
            else -> {
                Session.items = media
                startActivity(Intent(this, ViewerActivity::class.java).putExtra("pos", media.indexOf(f)).putExtra("trash", mode == Mode.TRASH))
            }
        }
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
        else confirm(if (permanent) "Supprimer définitivement ${files.size} fichier(s) ?" else "Mettre ${files.size} fichier(s) à la corbeille ?", run)
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
