package com.iurika.galleryflows

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ViewerActivity : Base() {
    private var items: MutableList<File> = mutableListOf()
    private var pos = 0
    private val trashMode by lazy { intent.getBooleanExtra("trash", false) }
    private lateinit var content: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var details: TextView
    private lateinit var hint: TextView
    private lateinit var videoRow: LinearLayout
    private lateinit var playPause: ImageView
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeEnd: TextView
    private lateinit var star: ImageView
    private var image: ZoomImageView? = null
    private var video: VideoView? = null
    private var videoFile: File? = null
    private var barsVisible = true
    private val handler = Handler(Looper.getMainLooper())
    private var slideshow = false
    private val nextSlide = object : Runnable {
        override fun run() {
            if (pos < items.lastIndex) { pos++; show(); handler.postDelayed(this, Prefs.slideSeconds * 1000L) } else stopSlideshow()
        }
    }
    private val hideHint = Runnable { hint.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            val v = video ?: return
            if (v.duration > 0) {
                seek.max = v.duration
                if (!seek.isPressed) seek.progress = v.currentPosition
                timeNow.text = DateUtils.formatElapsedTime(v.currentPosition / 1000L)
                timeEnd.text = DateUtils.formatElapsedTime(v.duration / 1000L)
            }
            playPause.setImageResource(if (v.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            handler.postDelayed(this, 250)
        }
    }

    // Swipe left/right = next/previous, swipe down = close. Ignored while zoomed, so panning stays panning.
    private val flings by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (image?.isZoomed == true) return false
                if (abs(vx) > abs(vy) && abs(vx) > 800) { step(if (vx < 0) 1 else -1); return true }
                val gesturesOwnVertical = video != null && Prefs.videoGestures
                if (Prefs.swipeDownClose && !gesturesOwnVertical && vy > 1500 && vy > abs(vx)) { closeDown(); return true }
                return false
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val uri = intent.data
        if (uri != null && intent.action == Intent.ACTION_VIEW) {
            // opened from another app: show that file among the other media of its folder
            thread {
                val f = runCatching { resolve(uri) }.getOrNull()
                runOnUiThread {
                    if (f == null) { toast("Impossible d'ouvrir ce fichier"); finish(); return@runOnUiThread }
                    val siblings = f.parentFile?.let { Scan.sortFiles(Scan.children(it).second) }.orEmpty()
                    items = (if (f in siblings) siblings else listOf(f)).toMutableList()
                    pos = items.indexOf(f)
                    show()
                }
            }
            return
        }
        items = Session.items.toMutableList()
        pos = intent.getIntExtra("pos", 0)
        if (pos !in items.indices) return finish() // process was killed, the list is gone
        show()
    }

    private fun buildUi() {
        if (Prefs.maxBrightness) window.attributes = window.attributes.apply { screenBrightness = 1f }
        content = FrameLayout(this)
        title = TextView(this).apply { setTextColor(WHITE); textSize = 18f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = shade(GradientDrawable.Orientation.TOP_BOTTOM)
            addView(icon(R.drawable.ic_back) { finish() })
            addView(title, LP(0, -2, 1f))
            addView(icon(R.drawable.ic_more) { menu(it) })
        }
        star = icon(R.drawable.ic_star) { toggleFavorite() }
        val buttons = if (trashMode) listOf(
            icon(R.drawable.ic_restore) { restore() },
            icon(R.drawable.ic_delete) { delete() },
            icon(R.drawable.ic_info) { info() },
        ) else listOf(
            star,
            icon(R.drawable.ic_edit) { edit() },
            icon(R.drawable.ic_share) { share(listOf(current())) },
            icon(R.drawable.ic_delete) { delete() },
            icon(R.drawable.ic_info) { info() },
        )
        timeNow = TextView(this).apply { setTextColor(WHITE); textSize = 13f }
        timeEnd = TextView(this).apply { setTextColor(WHITE); textSize = 13f }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) video?.seekTo(p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        playPause = icon(R.drawable.ic_play) { video?.let { if (it.isPlaying) it.pause() else startVideo() } }
        videoRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(4), 0, dp(12), 0)
            addView(playPause, LP(dp(48), dp(48)))
            addView(timeNow)
            addView(seek, LP(0, -2, 1f))
            addView(timeEnd)
        }
        details = TextView(this).apply { setTextColor(WHITE); textSize = 13f; setShadowLayer(4f, 0f, 0f, 0xff000000.toInt()); setPadding(dp(16), 0, dp(16), dp(4)) }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shade(GradientDrawable.Orientation.BOTTOM_TOP)
            addView(details)
            addView(videoRow)
            addView(LinearLayout(this@ViewerActivity).apply { buttons.forEach { addView(it, LP(0, dp(56), 1f)) } })
        }
        hint = TextView(this).apply {
            setTextColor(WHITE); textSize = 16f; background = rounded(0xaa000000.toInt(), 12); setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff000000.toInt())
            addView(content)
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            setOnApplyWindowInsetsListener { _, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                topBar.setPadding(b.left, b.top, b.right, dp(8))
                bottomBar.setPadding(b.left, dp(8), b.right, b.bottom)
                ins
            }
        })
        window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Prefs.autoHideUi) toggleBars()
    }

    private fun shade(o: GradientDrawable.Orientation) = GradientDrawable(o, intArrayOf(0xaa000000.toInt(), 0))

    private fun current() = items[pos]

    /** A content:// or file:// URI from another app, as a real file we can browse next to. */
    private fun resolve(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)
        if (uri.authority == "com.iurika.galleryflows.files") return File(uri.path!!)
        runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.let(::File)?.takeIf { it.canRead() }?.let { return it }
        if (uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isDocumentUri(this, uri)) {
            val (volume, rel) = DocumentsContract.getDocumentId(uri).split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val base = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
            File(base, rel).takeIf { it.canRead() }?.let { return it }
        }
        // last resort: a private copy, viewable but outside any folder
        var name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "media"
        if (kindOf(name) == null) MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri))?.let { name += ".$it" }
        val out = File(cacheDir, "opened/$name").apply { parentFile?.mkdirs() }
        contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
        return out
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (slideshow && e.action == MotionEvent.ACTION_DOWN) stopSlideshow()
        if (e.pointerCount == 1) flings.onTouchEvent(e)
        return super.dispatchTouchEvent(e)
    }

    override fun onUnlocked() {
        val edited = Session.edited ?: return
        Session.edited = null
        if (items.isEmpty()) return
        if (edited != current()) { items.add(pos + 1, edited); pos++ } // saved as a copy: show the copy
        show()
    }

    override fun onPause() { super.onPause(); savePosition(); video?.pause() }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null) }

    private fun step(delta: Int) {
        val n = pos + delta
        if (n !in items.indices) return
        pos = n; show()
        // the next picture comes in from the side it was swiped from
        content.translationX = content.width * .3f * delta; content.alpha = .3f
        content.animate().translationX(0f).alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeDown() {
        content.animate().translationY(content.height.toFloat()).alpha(0f).setDuration(200).withEndAction {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
        }.start()
    }

    private fun show() {
        savePosition()
        video?.stopPlayback()
        content.removeAllViews()
        image = null; video = null; videoFile = null
        videoRow.visibility = View.GONE
        handler.removeCallbacks(tick)
        val f = current()
        title.text = f.name
        star.setColorFilter(if (f.path in Prefs.favorites) ACCENT else WHITE)
        renderDetails(f)
        if (kindOf(f.name) == Kind.VIDEO && !Prefs.externalPlayer) showVideo(f) else showImage(f)
    }

    private fun renderDetails(f: File) {
        details.visibility = if (Prefs.showDetails) View.VISIBLE else View.GONE
        if (!Prefs.showDetails) return
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (kindOf(f.name) != Kind.VIDEO) BitmapFactory.decodeFile(f.path, o)
        details.text = listOfNotNull(
            o.outWidth.takeIf { it > 0 }?.let { "$it × ${o.outHeight}" },
            Formatter.formatShortFileSize(this, f.length()),
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified())),
        ).joinToString("  ·  ")
    }

    private fun showImage(f: File) {
        val v = ZoomImageView(this).apply { onTap = ::tapped }
        image = v
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        Thumbs.cached(f)?.let(v::setImageBitmap) // instant preview while the full image decodes
        if (kindOf(f.name) == Kind.VIDEO) { // external player mode: poster + play button
            thread {
                val poster = runCatching { ThumbnailUtils.createVideoThumbnail(f, Size(1280, 1280), null) }.getOrNull()
                runOnUiThread { if (image === v && poster != null) v.setImageBitmap(poster) }
            }
            content.addView(playButton { openWith(f, Intent.ACTION_VIEW, "Lire avec") }, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            return
        }
        thread {
            val d: Drawable? = runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    // keep the longest side under 4096 px: bigger bitmaps can't be drawn
                    val side = max(info.size.width, info.size.height)
                    dec.setTargetSampleSize((side + 4095) / 4096)
                }
            }.getOrNull()
            runOnUiThread {
                if (image !== v) return@runOnUiThread
                if (d == null) toast("Impossible d'ouvrir ${f.name}")
                else { v.setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() }
            }
        }
    }

    private fun tapped(x: Float) {
        val w = content.width
        when {
            Prefs.tapSides && x < w * 0.2f -> step(-1)
            Prefs.tapSides && x > w * 0.8f -> step(1)
            else -> toggleBars()
        }
    }

    private fun playButton(onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(R.drawable.ic_play); background = rounded(0x88000000.toInt(), 36); setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { onClick() }
    }

    private lateinit var playOverlay: ImageView

    private fun startVideo() {
        playOverlay.visibility = View.GONE
        video?.start()
    }

    private fun showVideo(f: File) {
        val v = VideoView(this)
        video = v; videoFile = f
        playOverlay = playButton { startVideo(); if (barsVisible) toggleBars() }
        v.setOnPreparedListener { mp ->
            mp.isLooping = Prefs.loopVideos
            v.seekTo(max(1, if (Prefs.rememberPosition) Prefs.position(f) else 0)) // a frame instead of black
            if (Prefs.autoplay) startVideo()
        }
        v.setOnCompletionListener { playOverlay.visibility = View.VISIBLE; Prefs.savePosition(f, 0) }
        v.setOnErrorListener { _, _, _ -> toast("Lecture impossible, essaie « Ouvrir avec »"); true }
        v.setVideoPath(f.path)
        content.addView(v, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        content.addView(gestureLayer(), FrameLayout.LayoutParams(-1, -1))
        content.addView(playOverlay, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        videoRow.visibility = View.VISIBLE
        seek.progress = 0; timeNow.text = ""; timeEnd.text = ""
        handler.removeCallbacks(tick); handler.post(tick)
    }

    // Tap: show / hide the bars and controls. Vertical drag: brightness on the left half, volume on the right half.
    private fun gestureLayer() = View(this).apply {
        val audio = getSystemService(AudioManager::class.java)
        var pending = 0f
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { pending = 0f; return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleBars()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (!Prefs.videoGestures || e1 == null || abs(dy) < abs(dx)) return false
                if (e1.x < width / 2) {
                    val attrs = window.attributes
                    val now = (if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness) + dy / height
                    attrs.screenBrightness = now.coerceIn(0.01f, 1f)
                    window.attributes = attrs
                    flash("Luminosité ${(attrs.screenBrightness * 100).roundToInt()} %")
                } else {
                    pending += dy
                    val stepPx = dp(30)
                    while (abs(pending) >= stepPx) {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (pending > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                        pending -= if (pending > 0) stepPx else -stepPx
                    }
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    flash("Volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max} %")
                }
                return true
            }
        })
        setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    private fun flash(text: String) {
        hint.text = text; hint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint); handler.postDelayed(hideHint, 800)
    }

    private fun savePosition() {
        val v = video ?: return
        val f = videoFile ?: return
        if (!Prefs.rememberPosition) return
        val nearEnd = v.duration > 0 && v.duration - v.currentPosition < 2000
        Prefs.savePosition(f, if (nearEnd) 0 else v.currentPosition)
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val vis = if (barsVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis; bottomBar.visibility = vis
        window.insetsController?.let { if (barsVisible) it.show(WindowInsets.Type.systemBars()) else it.hide(WindowInsets.Type.systemBars()) }
    }

    // ---- actions ----

    private fun toggleFavorite() {
        val p = current().path
        Prefs.favorites = if (p in Prefs.favorites) Prefs.favorites - p else Prefs.favorites + p
        star.setColorFilter(if (p in Prefs.favorites) ACCENT else WHITE)
        Session.dirty = true
    }

    private fun edit() {
        val f = current()
        if (kindOf(f.name) == Kind.IMAGE) startActivity(Intent(this, EditorActivity::class.java).putExtra("path", f.path))
        else openWith(f, Intent.ACTION_EDIT, "Modifier avec")
    }

    private fun removeCurrent() {
        savePosition(); video?.stopPlayback(); video = null
        items.removeAt(pos)
        Session.dirty = true
        if (items.isEmpty()) return finish()
        pos = min(pos, items.lastIndex)
        show()
    }

    private fun delete() {
        val f = current()
        val permanent = trashMode || !Prefs.useTrash
        val run = { guardOps { Ops.delete(this, listOf(f), permanent); if (f.exists()) toast("Suppression impossible") else removeCurrent() } }
        if (Prefs.skipDeleteConfirm) run()
        else confirm(if (permanent) "Supprimer définitivement ${f.name} ?" else "Mettre ${f.name} à la corbeille ?", run)
    }

    private fun restore() {
        val f = current()
        toast(Trash.restore(this, listOf(f)))
        if (!f.exists()) removeCurrent()
    }

    private fun transfer(move: Boolean) {
        val f = current()
        pickFolder(if (move) "Déplacer vers" else "Copier vers", f.parentFile) { dest ->
            guardOps {
                thread {
                    val msg = Ops.transfer(this, listOf(f), dest, move)
                    runOnUiThread { toast(msg); Session.dirty = true; if (move && !f.exists()) removeCurrent() }
                }
            }
        }
    }

    private fun rename(newName: String) {
        val f = Ops.rename(this, current(), newName) ?: return toast("Impossible de renommer")
        items[pos] = f
        Session.dirty = true
        show()
    }

    private fun rotate() {
        val f = current()
        if (!Images.writable(f)) return toast("Pivotement possible pour JPEG, PNG et WebP")
        choose("Pivoter", listOf("Vers la droite" to 90, "Vers la gauche" to 270, "À 180°" to 180).map { (label, deg) ->
            label to {
                thread {
                    val ok = Images.rotate(this, f, deg)
                    runOnUiThread { if (ok) { Session.dirty = true; show() } else toast("Impossible de pivoter") }
                }
                Unit
            }
        })
    }

    private fun resize() {
        val f = current()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
        choose("Redimensionner (copie)", listOf(75, 50, 33, 25).map { pct ->
            val size = if (o.outWidth > 0) " · ${o.outWidth * pct / 100} × ${o.outHeight * pct / 100}" else ""
            "$pct %$size" to {
                toast("Redimensionnement…")
                thread {
                    val out = runCatching { Images.resize(f, pct / 100f) }.getOrNull()
                    runOnUiThread {
                        if (out == null) return@runOnUiThread toast("Impossible de redimensionner")
                        Ops.rescan(this, listOf(out.path)); Session.dirty = true
                        items.add(pos + 1, out); toast("Enregistré : ${out.name}")
                    }
                }
                Unit
            }
        })
    }

    private fun menu(anchor: View) {
        val f = current()
        val isImage = kindOf(f.name) != Kind.VIDEO
        val actions = if (trashMode) listOf<Pair<String, () -> Unit>>(
            "Restaurer" to ::restore,
            "Supprimer définitivement" to ::delete,
            "Ouvrir avec" to { openWith(f, Intent.ACTION_VIEW, "Ouvrir avec") },
        ) else listOfNotNull<Pair<String, () -> Unit>>(
            "Copier vers" to { transfer(move = false) },
            "Déplacer vers" to { transfer(move = true) },
            "Renommer" to { ask("Renommer", f.name, ::rename) },
            if (isImage) "Pivoter" to ::rotate else null,
            if (isImage) "Redimensionner" to ::resize else null,
            "Modifier avec…" to { openWith(f, Intent.ACTION_EDIT, "Modifier avec") },
            "Cacher" to { if (Ops.rename(this, f, ".${f.name}") != null) removeCurrent() else toast("Impossible de cacher") },
            "Ouvrir avec" to { openWith(f, Intent.ACTION_VIEW, "Ouvrir avec") },
            "Définir en tant que" to { openWith(f, Intent.ACTION_ATTACH_DATA, "Définir en tant que") },
            if (isImage) "Afficher sur la carte" to { Images.showOnMap(this, f) } else null,
            if (isImage) "Imprimer" to { Images.print(this, f); Unit } else null,
            "Diaporama" to ::startSlideshow,
            "Paramètres" to { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        PopupMenu(this, anchor).apply {
            actions.forEachIndexed { i, a -> menu.add(0, i, i, a.first) }
            setOnMenuItemClickListener { actions[it.itemId].second(); true }
            show()
        }
    }

    private fun startSlideshow() {
        slideshow = true
        if (barsVisible) toggleBars()
        handler.postDelayed(nextSlide, Prefs.slideSeconds * 1000L)
    }

    private fun stopSlideshow() {
        slideshow = false
        handler.removeCallbacks(nextSlide)
    }

    private fun info() {
        val f = current()
        val lines = mutableListOf(
            "Nom : ${f.name}",
            "Dossier : ${Trash.originalOf(f)?.let { File(it).parent } ?: f.parent}",
            "Taille : ${Formatter.formatFileSize(this, f.length())}",
            "Modifié : ${DateFormat.getDateTimeInstance().format(Date(f.lastModified()))}",
        )
        runCatching {
            if (kindOf(f.name) == Kind.VIDEO) MediaMetadataRetriever().use { r ->
                r.setDataSource(f.path)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                lines += "Résolution : $w × $h"
                lines += "Durée : ${DateUtils.formatElapsedTime(ms / 1000)}"
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth > 0) lines += "Résolution : ${o.outWidth} × ${o.outHeight}"
                val e = ExifInterface(f.path)
                e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { lines += "Prise de vue : $it" }
                listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL))
                    .joinToString(" ").takeIf { it.isNotBlank() }?.let { lines += "Appareil : $it" }
                Images.latLong(f)?.let { lines += "GPS : %.5f, %.5f".format(it[0], it[1]) }
            }
        }
        AlertDialog.Builder(this).setTitle("Propriétés").setMessage(lines.joinToString("\n\n")).setPositiveButton("OK", null).show()
    }
}

/** ImageView with pinch zoom, double-tap zoom and panning. */
class ZoomImageView(c: Context) : ImageView(c) {
    var onTap: (Float) -> Unit = {}
    private val m = Matrix()
    private var scale = 1f // relative to "fit screen"
    val isZoomed get() = scale > 1.01f

    private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomTo(scale * d.scaleFactor, d.focusX, d.focusY); return true }
    })
    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (isZoomed) { m.postTranslate(-dx, -dy); fix() }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            ValueAnimator.ofFloat(scale, if (isZoomed) 1f else 2.5f).apply {
                duration = 200; interpolator = DecelerateInterpolator()
                addUpdateListener { zoomTo(it.animatedValue as Float, e.x, e.y) }
            }.start()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(e.x); return true }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    override fun setImageDrawable(d: Drawable?) { super.setImageDrawable(d); reset() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); reset() }

    private fun reset() {
        val d = drawable ?: return
        if (width == 0 || d.intrinsicWidth <= 0) return
        val s = min(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        m.setScale(s, s)
        m.postTranslate((width - d.intrinsicWidth * s) / 2, (height - d.intrinsicHeight * s) / 2)
        scale = 1f
        imageMatrix = m
    }

    private fun zoomTo(target: Float, x: Float, y: Float) {
        val f = target.coerceIn(1f, 8f) / scale
        scale *= f
        m.postScale(f, f, x, y)
        fix()
    }

    // Center the image when it's smaller than the screen, otherwise keep its edges on the screen edges
    private fun fix() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        fun shift(start: Float, end: Float, size: Float) = when {
            end - start <= size -> size / 2 - (start + end) / 2
            start > 0 -> -start
            end < size -> size - end
            else -> 0f
        }
        m.postTranslate(shift(r.left, r.right, width.toFloat()), shift(r.top, r.bottom, height.toFloat()))
        imageMatrix = m
    }
}
