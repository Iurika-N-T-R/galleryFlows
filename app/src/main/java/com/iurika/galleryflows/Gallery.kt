package com.iurika.galleryflows

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Order matters: Prefs.hiddenKinds has one bit per ordinal
enum class Kind { IMAGE, VIDEO, GIF, RAW, SVG, PORTRAIT }

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp")
private val RAW_EXT = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "pef", "srw")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts")

fun kindOf(name: String): Kind? = when (name.substringAfterLast('.', "").lowercase()) {
    "gif" -> Kind.GIF
    "svg" -> Kind.SVG
    in RAW_EXT -> Kind.RAW
    // portrait-mode shots: Pixel "PXL_…PORTRAIT", Samsung and others "…_portrait"
    in IMAGE_EXT -> if (name.contains("portrait", ignoreCase = true)) Kind.PORTRAIT else Kind.IMAGE
    in VIDEO_EXT -> Kind.VIDEO
    else -> null
}

/** A plain photo: what the editor and in-place rotation work on. */
fun isPhoto(name: String) = kindOf(name).let { it == Kind.IMAGE || it == Kind.PORTRAIT }

class TrashEntry(val original: String, val time: Long)

object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var positions: SharedPreferences

    private fun bool(def: Boolean) = object : ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getBoolean(property.name, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) = sp.edit().putBoolean(property.name, value).apply()
    }

    private fun int(def: Int) = object : ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getInt(property.name, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) = sp.edit().putInt(property.name, value).apply()
    }

    // browsing
    var sort by int(1) // 0 name, 1 modified, 2 size, 3 random, 4 date taken, 5 location, 6 dimension, 7 custom
    var desc by bool(true)
    var folderCols by int(2)
    var mediaCols by int(4)
    var listView by bool(false)
    var showHidden by bool(false)
    var hiddenKinds by int(0) // bit per Kind.ordinal
    var searchFiles by bool(true) // the search box finds files; off: folders
    var loadPriority by int(1) // 0 fast (small thumbnails), 1 balanced, 2 best quality
    var horizontal by bool(false)
    var pullRefresh by bool(false)
    // look
    var accent by int(0) // index in ACCENTS
    var background by int(0) // index in BACKGROUNDS
    var english by bool(false)
    var dateFormat by int(1) // index in DATE_FORMATS
    var hour24 by bool(true)
    // thumbnails
    var cropThumbs by bool(true)
    var animateGifs by bool(true)
    var fileStyle by int(0) // 0 square, 1 rounded
    var folderStyle by int(1) // 0 square, 1 rounded, 2 circle
    // videos
    var autoplay by bool(false)
    var loopVideos by bool(false)
    var rememberPosition by bool(true)
    var externalPlayer by bool(false)
    var videoGestures by bool(true)
    // fullscreen
    var maxBrightness by bool(false)
    var autoHideUi by bool(false)
    var tapSides by bool(false)
    var swipeDownClose by bool(true)
    var showDetails by bool(false)
    var blackBg by bool(true)
    var imageBrightness by bool(false) // vertical drags on the left half of a picture
    var showNotch by bool(true) // keep pictures clear of the camera cutout
    var orientation by int(0) // 0 system, 1 portrait, 2 landscape, 3 follows the media
    // zoom
    var advancedZoom by bool(true)
    var gestureRotate by bool(true)
    var bestQuality by bool(true) // sharp tiles from the full file when zoomed in
    var oneToOne by bool(false) // double tap zooms to the real pixels
    // viewer action bar
    var showActions by bool(true)
    var slideSeconds by int(3) // slideshow and shuffle timer
    var shuffleSub by bool(true) // shuffle takes the subfolders too
    // security
    var appLock by bool(false)
    var lockHidden by bool(false)
    var lockOps by bool(false)
    // file operations
    var skipDeleteConfirm by bool(false)
    var deleteEmptyFolders by bool(true)
    var keepDates by bool(true)
    // trash
    var useTrash by bool(true)
    var showTrash by bool(true)

    // Lists stay in memory: the scan checks `excluded` for every folder it walks
    var roots: List<String> = emptyList()
        set(v) { field = v.distinct(); put("roots", field) }
    var favorites: Set<String> = emptySet()
        set(v) { field = v; put("favorites", v) }
    var excluded: Set<String> = emptySet()
        set(v) { field = v; put("excluded", v) }
    var pinned: Set<String> = emptySet()
        set(v) { field = v; put("pinned", v) }
    var custom: List<String> = emptyList() // "Personnalisé" order: these paths first, in this order
        set(v) { field = v.distinct().take(5000); put("custom", field) }
    var targets: List<String> = emptyList() // last copy / move destinations, newest first
        set(v) { field = v.distinct().take(6); put("targets", field) }
    var bookmarks: Map<String, List<String>> = emptyMap() // name -> folders and files, in the order added
        set(v) { field = v; sp.edit().putString("bookmarks", JSONObject(v.mapValues { JSONArray(it.value) }).toString()).apply() }
    var actions: List<String> = emptyList() // viewer bottom bar, keys of VIEWER_ACTIONS
        set(v) { field = v; put("actions", v) }
    var trash: Map<String, TrashEntry> = emptyMap() // trashed path -> where it came from
        set(v) { field = v; sp.edit().putString("trash", v.entries.joinToString("\n") { "${it.key}\t${it.value.original}\t${it.value.time}" }).apply() }

    fun init(c: Context) {
        if (::sp.isInitialized) return
        sp = c.getSharedPreferences("galleryflows", Context.MODE_PRIVATE)
        positions = c.getSharedPreferences("positions", Context.MODE_PRIVATE)
        migrate()
        loadLists()
    }

    // 0.3: a bit per shown kind (3 kinds) and a rounded-folders flag
    private fun migrate() {
        if (sp.contains("kinds")) { hiddenKinds = sp.getInt("kinds", 7).inv() and 7; sp.edit().remove("kinds").apply() }
        if (sp.contains("roundFolders")) { folderStyle = if (sp.getBoolean("roundFolders", true)) 1 else 0; sp.edit().remove("roundFolders").apply() }
    }

    private fun loadLists() {
        roots = if (sp.contains("roots")) strings("roots")
        else listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES).map { Environment.getExternalStoragePublicDirectory(it).path }
        favorites = strings("favorites").toSet()
        excluded = strings("excluded").toSet()
        pinned = strings("pinned").toSet()
        custom = strings("custom")
        targets = strings("targets")
        bookmarks = runCatching {
            val o = JSONObject(sp.getString("bookmarks", null) ?: "{}")
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> List(a.length(), a::getString) } }
        }.getOrDefault(emptyMap())
        actions = if (sp.contains("actions")) strings("actions") else listOf("favorite", "edit", "share", "delete", "info")
        trash = strings("trash").mapNotNull { line ->
            line.split('\t').takeIf { it.size == 3 }?.let { (t, o, time) -> t to TrashEntry(o, time.toLongOrNull() ?: 0) }
        }.toMap()
    }

    fun renamed(old: File, new: File) {
        if (old.path in favorites) favorites = favorites - old.path + new.path
        if (old.path in pinned) pinned = pinned - old.path + new.path
        if (old.path in roots) roots = roots.map { if (it == old.path) new.path else it }
        if (old.path in custom) custom = custom.map { if (it == old.path) new.path else it }
        if (bookmarks.values.any { old.path in it }) bookmarks = bookmarks.mapValues { (_, l) -> l.map { if (it == old.path) new.path else it } }
    }

    fun position(f: File) = positions.getInt(f.path, 0)
    fun savePosition(f: File, ms: Int) = positions.edit().apply { if (ms > 0) putInt(f.path, ms) else remove(f.path) }.apply()

    fun export(): String = JSONObject(sp.all).toString(2)

    fun import(json: String) {
        val o = JSONObject(json)
        sp.edit().apply {
            for (k in o.keys()) when (val v = o.get(k)) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is String -> putString(k, v)
            }
        }.commit()
        migrate()
        loadLists()
    }

    private fun strings(k: String) = sp.getString(k, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
    private fun put(k: String, v: Collection<String>) = sp.edit().putString(k, v.joinToString("\n")).apply()
}

data class Info(val count: Int, val cover: File?, val newest: Long)

object Scan {
    private lateinit var ctx: Context
    @Volatile private var cache = ConcurrentHashMap<String, Info>()
    @Volatile private var cleared = 0
    private val saved get() = File(ctx.filesDir, "scan") // not cacheDir: Android empties that whenever storage runs low
    var stale = false // the cache came from disk: show it at once, then [rescan]

    // Walking a big tree costs one stat per file, too slow for every launch: the last walk is kept on disk
    fun init(c: Context) {
        ctx = c.applicationContext
        File(c.cacheDir, "scan").takeIf { it.exists() && !saved.exists() }?.renameTo(saved)
        runCatching {
            saved.forEachLine { line ->
                val (path, count, cover, newest) = line.split('\t')
                cache[path] = Info(count.toInt(), cover.takeIf { it.isNotEmpty() }?.let(::File), newest.toLong())
            }
        }
        stale = cache.isNotEmpty()
    }

    fun save() = runCatching {
        val tmp = File(ctx.filesDir, "scan.tmp")
        tmp.writeText(cache.entries.joinToString("\n") { (p, i) -> "$p\t${i.count}\t${i.cover?.path.orEmpty()}\t${i.newest}" })
        tmp.renameTo(saved)
    }

    /** Walks every root again in the background and swaps the result in, unless a [clear] happened meanwhile. */
    fun rescan() {
        val gen = cleared
        val fresh = ConcurrentHashMap<String, Info>()
        Prefs.roots.forEach { info(File(it), into = fresh) }
        if (gen == cleared) { cache = fresh; save() }
    }

    fun clear() { cleared++; cache.clear() }

    private fun visible(f: File) = (Prefs.showHidden || !f.name.startsWith('.')) && f.path !in Prefs.excluded && f.name != Trash.DIR
    private fun wanted(f: File) = kindOf(f.name)?.let { Prefs.hiddenKinds and (1 shl it.ordinal) == 0 } == true && visible(f)

    fun hasNomedia(dir: File) = File(dir, ".nomedia").exists()

    /** Direct subfolders and media of one folder, unsorted. */
    fun children(dir: File): Pair<List<File>, List<File>> {
        val dirs = ArrayList<File>()
        val media = ArrayList<File>()
        for (f in dir.listFiles().orEmpty()) if (f.isDirectory) { if (visible(f)) dirs += f } else if (wanted(f)) media += f
        return dirs to media
    }

    // Media count and newest file of a whole subtree, memoized so one walk of a root caches every folder below it
    fun info(dir: File, depth: Int = 0, into: MutableMap<String, Info> = cache): Info = into[dir.path] ?: run {
        val files = dir.listFiles().orEmpty()
        var count = 0
        var cover: File? = null
        var newest = 0L
        if (depth < 32 && (Prefs.showHidden || files.none { it.name == ".nomedia" })) for (f in files) {
            val i = if (f.isDirectory) { if (!visible(f)) continue; info(f, depth + 1, into) }
            else if (wanted(f)) Info(1, f.takeIf { kindOf(it.name) != Kind.SVG }, f.lastModified()) else continue // an SVG's tile makes a poor cover
            count += i.count
            if (i.cover != null && i.newest >= newest) { newest = i.newest; cover = i.cover }
        }
        Info(count, cover, newest).also { into[dir.path] = it }
    }

    /** Media whose name contains [q], anywhere below [dirs]. */
    fun search(dirs: List<File>, q: String) = sortFiles(collect(dirs, 5000) { it.name.contains(q, ignoreCase = true) })

    /** Folders with media whose name contains [q], anywhere below [dirs]. */
    fun searchFolders(dirs: List<File>, q: String): List<File> {
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (depth > 32 || out.size >= 500 || (!Prefs.showHidden && hasNomedia(d))) return
            for (sub in children(d).first) {
                if (info(sub).count == 0) continue
                if (sub.name.contains(q, ignoreCase = true)) out += sub
                walk(sub, depth + 1)
            }
        }
        dirs.forEach { walk(it, 0) }
        return sortFolders(out)
    }

    /** Every shown media below [dirs] (unsorted), up to [max]. */
    fun collect(dirs: List<File>, max: Int = 50_000, keep: (File) -> Boolean = { true }): List<File> {
        val out = LinkedHashSet<File>()
        fun walk(d: File, depth: Int) {
            if (depth > 32 || out.size >= max) return
            if (!Prefs.showHidden && hasNomedia(d)) return
            val (sub, media) = children(d)
            media.filterTo(out, keep)
            sub.forEach { walk(it, depth + 1) }
        }
        dirs.forEach { walk(it, 0) }
        return out.toList()
    }

    fun sortFiles(files: List<File>) = order(files, File::getPath, File::lastModified, File::length,
        taken = { val dates = indexed(files, MediaStore.MediaColumns.DATE_TAKEN); { f -> dates[f.path]?.get(0) ?: f.lastModified() } },
        dims = {
            val sizes = indexed(files, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
            val key: (File) -> Long = { f -> sizes[f.path]?.let { it[0] * it[1] } ?: pixels(f) }
            key
        })

    // Pinned folders first, then the chosen order
    fun sortFolders(dirs: List<File>): List<File> {
        val sorted = order(dirs, File::getPath, { info(it).newest }, { info(it).count.toLong() }, { { info(it).newest } }, { { info(it).count.toLong() } })
        return sorted.filter { it.path in Prefs.pinned } + sorted.filter { it.path !in Prefs.pinned }
    }

    private fun <T> order(items: List<T>, path: (T) -> String, date: (T) -> Long, size: (T) -> Long, taken: () -> (T) -> Long, dims: () -> (T) -> Long): List<T> {
        val name = { t: T -> path(t).substringAfterLast('/') }
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER, name)
        val sorted = when (Prefs.sort) {
            0 -> items.sortedWith(byName)
            3 -> return items.shuffled()
            5 -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, path))
            7 -> { // the hand-placed ones first, the rest by name; no direction
                val at = Prefs.custom.withIndex().associate { it.value to it.index }
                return items.sortedWith(compareBy<T> { at[path(it)] ?: Int.MAX_VALUE }.then(byName))
            }
            else -> {
                val key = when (Prefs.sort) { 1 -> date; 2 -> size; 6 -> dims(); else -> taken() }
                items.map { it to key(it) }.sortedBy { it.second }.map { it.first }
            }
        }
        return if (Prefs.desc) sorted.asReversed() else sorted
    }

    private fun pixels(f: File): Long {
        if (kindOf(f.name) == Kind.VIDEO) return 0
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth.toLong() * o.outHeight
    }

    // Capture dates and sizes come from the system MediaStore, which already indexed them; files it doesn't know fall back
    private fun indexed(files: List<File>, vararg cols: String): Map<String, LongArray> {
        val out = HashMap<String, LongArray>()
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns.DATA, *cols)
        files.map { it.path }.chunked(900).forEach { paths ->
            runCatching {
                ctx.contentResolver.query(uri, projection, "${projection[0]} IN (${paths.joinToString(",") { "?" }})", paths.toTypedArray(), null)?.use { c ->
                    while (c.moveToNext()) {
                        val v = LongArray(cols.size) { c.getLong(it + 1) }
                        if (v.all { it > 0 }) out[c.getString(0)] = v
                    }
                }
            }
        }
        return out
    }
}

/** Hands the viewer its list without squeezing thousands of paths through an Intent. */
object Session {
    var items: List<File> = emptyList()
    var locate: ((File) -> RectF?)? = null // where the browser shows this file's thumbnail, for the viewer's way back
    var dirty = false // a file changed in the viewer: the browser must rescan
    var edited: File? = null // the editor saved this file: the viewer shows it on return
}

/** App lock state: locked again whenever every screen of the app has left the foreground. */
object Lock {
    var locked = false
    var prompting = false
}
