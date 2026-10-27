package com.iurika.galleryflows

import android.animation.ValueAnimator
import java.util.concurrent.Executors
import android.view.animation.PathInterpolator
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.graphics.drawable.ColorDrawable
import android.graphics.Rect
import android.graphics.Bitmap
import android.animation.AnimatorListenerAdapter
import android.animation.Animator
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
    private var loopSlides = false // shuffle: start over at the end instead of stopping
    // next media, cross-faded; show() schedules the following one
    private val nextSlide = Runnable {
        val n = when { pos < items.lastIndex -> pos + 1; loopSlides -> 0; else -> -1 }
        if (n < 0) return@Runnable stopSlideshow()
        pos = n; show()
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(400).start()
    }

    private lateinit var root: FrameLayout
    private val bg = ColorDrawable(0xff000000.toInt())
    private val standard = PathInterpolator(.2f, 0f, 0f, 1f) // Material standard easing
    private var animating = false
    private var closing = false
    // Screen-sized decodes of the media around the current one, so a swipe shows them sharp at once
    private val previews = HashMap<String, Bitmap>()
    private val loader = Executors.newSingleThreadExecutor()
    private var neighbor: ImageView? = null // the page coming in beside the current one during a swipe
    private val gap get() = dp(16)

    // Touch: 0 undecided, 1 swiping pages, 2 dragging down to close, -1 left to the views (zoom, pan, pinch, bars)
    private var dragMode = 0
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
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
                    open()
                }
            }
            return
        }
        items = Session.items.toMutableList()
        pos = intent.getIntExtra("pos", 0)
        if (pos !in items.indices) return finish() // process was killed, the list is gone
        show()
        open()
    }

    private fun buildUi() {
        if (Prefs.maxBrightness) window.attributes = window.attributes.apply { screenBrightness = 1f }
        content = FrameLayout(this)
        title = TextView(this).apply { setTextColor(WHITE); textSize = 18f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = shade(GradientDrawable.Orientation.TOP_BOTTOM)
            addView(icon(R.drawable.ic_back) { close() })
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
        root = FrameLayout(this).apply {
            background = bg
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
        }
        setContentView(root)
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

    // Swipes are handled here, above the views: sideways pages follow the finger, down drags the picture away.
    // Zoomed pictures, pinches and the bars keep their own touches.
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (closing || animating) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (slideshow && !loopSlides) stopSlideshow()
                downX = e.rawX; downY = e.rawY
                val onBars = barsVisible && (e.y < topBar.bottom || e.y > bottomBar.top)
                dragMode = if (image?.isZoomed == true || onBars) -1 else 0
                velocity?.recycle(); velocity = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (dragMode == 0) dragMode = -1
        }
        velocity?.addMovement(e)
        if (dragMode == 0 && e.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = e.rawX - downX
            val dy = e.rawY - downY
            val videoOwnsVertical = video != null && Prefs.videoGestures
            if (abs(dx) > slop && abs(dx) > abs(dy)) dragMode = 1
            else if (dy > slop && dy > abs(dx) && Prefs.swipeDownClose && !videoOwnsVertical) dragMode = 2
            if (dragMode > 0) {
                downX = e.rawX; downY = e.rawY // start from here, no jump of a slop
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel) // the views below stop seeing this gesture
                cancel.recycle()
            }
        }
        if (dragMode <= 0) return super.dispatchTouchEvent(e)
        val dx = e.rawX - downX
        val dy = e.rawY - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragMode == 1) dragPage(dx) else dragDown(dx, dy)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val v = velocity!!.apply { computeCurrentVelocity(1000) }
                val fast = dp(600)
                if (dragMode == 1) {
                    val delta = if (dx < 0) 1 else -1
                    val flung = abs(v.xVelocity) > fast && (v.xVelocity < 0) == (dx < 0)
                    settlePage(if (neighbor != null && (abs(dx) > root.width / 3 || flung)) delta else 0)
                } else if (dy > root.height * .12f || v.yVelocity > fast) close()
                else restoreDrag()
                dragMode = 0
            }
        }
        return true
    }

    // ---- paging ----

    private fun neighborFor(delta: Int): ImageView? {
        val n = pos + delta
        if (n !in items.indices) return null
        val nb = neighbor ?: ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            .also { neighbor = it; root.addView(it, 1, FrameLayout.LayoutParams(-1, -1)) }
        if (nb.tag != n) { nb.tag = n; nb.setImageBitmap(previews[items[n].path] ?: Thumbs.cached(items[n])) }
        return nb
    }

    private fun dropNeighbor() { neighbor?.let(root::removeView); neighbor = null }

    private fun dragPage(dx: Float) {
        val delta = if (dx < 0) 1 else -1
        val nb = neighborFor(delta)
        if (nb == null) dropNeighbor()
        val x = if (nb == null) dx / 3 else dx // resists past the first and last media
        content.translationX = x
        nb?.translationX = x + delta * (root.width + gap)
    }

    /** Ends a swipe: [delta] = 1 or -1 moves on, 0 springs back. */
    private fun settlePage(delta: Int) {
        val w = (root.width + gap).toFloat()
        val nb = neighbor
        animating = true
        nb?.animate()?.translationX(((nb.tag as Int) - pos - delta) * w)?.setDuration(250)?.setInterpolator(standard)?.start()
        content.animate().translationX(-delta * w).setDuration(250).setInterpolator(standard).withEndAction {
            animating = false
            if (delta != 0) { pos += delta; show() }
            content.translationX = 0f
            dropNeighbor()
        }.start()
    }

    private fun step(delta: Int) {
        val nb = neighborFor(delta) ?: return
        nb.translationX = delta * (root.width + gap).toFloat()
        settlePage(delta)
    }

    // ---- drag down to close ----

    private fun dragDown(dx: Float, dy: Float) {
        val p = (abs(dy) / root.height).coerceIn(0f, 1f)
        val k = 1 - p * .4f
        content.translationX = dx; content.translationY = dy
        content.scaleX = k; content.scaleY = k
        bg.alpha = (255 * (1 - p * 2.5f)).coerceAtLeast(0f).toInt()
        (1 - p * 6).coerceAtLeast(0f).let { topBar.alpha = it; bottomBar.alpha = it }
    }

    private fun restoreDrag() {
        content.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(standard).start()
        topBar.animate().alpha(1f).setDuration(200).start(); bottomBar.animate().alpha(1f).setDuration(200).start()
        fadeBg(255, 200)
    }

    private fun fadeBg(to: Int, duration: Long, done: () -> Unit = {}) {
        ValueAnimator.ofInt(bg.alpha, to).apply {
            this.duration = duration
            addUpdateListener { bg.alpha = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) = done() })
        }.start()
    }

    // ---- open and close: the picture grows out of its thumbnail and shrinks back into it ----

    private fun windowToRoot(r: RectF) = RectF(r).apply { val o = IntArray(2).also(root::getLocationInWindow); offset(-o[0].toFloat(), -o[1].toFloat()) }

    /** Where [bmp] sits fitted to the screen. */
    private fun fitted(bmp: Bitmap): RectF {
        val w = root.width.toFloat(); val h = root.height.toFloat()
        val s = min(w / bmp.width, h / bmp.height)
        return RectF((w - bmp.width * s) / 2, (h - bmp.height * s) / 2, (w + bmp.width * s) / 2, (h + bmp.height * s) / 2)
    }

    /** [fit] grown to cover [thumb] and centered on it: the picture as a center-cropped thumbnail shows it. */
    private fun covering(fit: RectF, thumb: RectF): RectF {
        val k = max(thumb.width() / fit.width(), thumb.height() / fit.height())
        val hw = fit.width() * k / 2; val hh = fit.height() * k / 2
        return RectF(thumb.centerX() - hw, thumb.centerY() - hh, thumb.centerX() + hw, thumb.centerY() + hh)
    }

    /**
     * A stand-in of [bmp] goes from [from] to [to], each a (picture rect, visible part) pair:
     * the picture moves and scales while its crop opens or closes, like Material's container transform.
     */
    private fun morph(bmp: Bitmap, from: Pair<RectF, RectF>, to: Pair<RectF, RectF>, bgTo: Int, duration: Long, done: () -> Unit) {
        val fit = fitted(bmp)
        val ghost = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(bmp); pivotX = 0f; pivotY = 0f }
        root.addView(ghost, 1, FrameLayout.LayoutParams(fit.width().roundToInt(), fit.height().roundToInt()))
        val bgFrom = bg.alpha
        val pic = RectF()
        val clip = RectF()
        fun lerp(a: RectF, b: RectF, t: Float, out: RectF) =
            out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
        animating = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration; interpolator = standard
            addUpdateListener {
                val t = it.animatedValue as Float
                lerp(from.first, to.first, t, pic); lerp(from.second, to.second, t, clip)
                val k = pic.width() / fit.width()
                ghost.scaleX = k; ghost.scaleY = k; ghost.translationX = pic.left; ghost.translationY = pic.top
                ghost.clipBounds = Rect(((clip.left - pic.left) / k).toInt(), ((clip.top - pic.top) / k).toInt(),
                    ((clip.right - pic.left) / k).roundToInt(), ((clip.bottom - pic.top) / k).roundToInt())
                bg.alpha = (bgFrom + (bgTo - bgFrom) * t).toInt()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { animating = false; done(); root.removeView(ghost) }
            })
        }.start()
    }

    private fun open() {
        bg.alpha = 0
        content.alpha = 0f; topBar.alpha = 0f; bottomBar.alpha = 0f
        @Suppress("DEPRECATION") val from = intent.getParcelableExtra<RectF>("from")
        val bmp = Thumbs.cached(current())
        root.post {
            listOf(topBar, bottomBar).forEach { it.animate().alpha(1f).setStartDelay(150).setDuration(150).start() }
            if (intent.getBooleanExtra("shuffle", false)) startSlideshow(loop = true)
            if (from != null && bmp != null && root.width > 0) {
                val thumb = windowToRoot(from)
                val fit = fitted(bmp)
                morph(bmp, covering(fit, thumb) to thumb, fit to fit, 255, 300) { content.alpha = 1f }
            } else {
                // nothing to grow from: fade through, growing from 92 %
                content.scaleX = .92f; content.scaleY = .92f
                content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(standard).start()
                fadeBg(255, 250)
            }
        }
    }

    private fun close() {
        if (closing) return
        closing = true
        stopSlideshow()
        video?.pause()
        val finishNow = { finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
        topBar.animate().alpha(0f).setDuration(100).start(); bottomBar.animate().alpha(0f).setDuration(100).start()
        val f = current()
        val bmp = Thumbs.cached(f)
        // only back into the grid it came from, not into a browser left open behind an "Open with"
        val target = Session.locate?.takeIf { intent.data == null }?.invoke(f)?.let(::windowToRoot)
        if (bmp != null && target != null && image?.isZoomed != true && root.width > 0) {
            val fit = fitted(bmp)
            // where the picture is right now, dragged or not: content scales about its center and follows the finger
            val k = content.scaleX
            val cx = root.width / 2f + content.translationX
            val cy = root.height / 2f + content.translationY
            val now = RectF(cx + (fit.left - root.width / 2f) * k, cy + (fit.top - root.height / 2f) * k,
                cx + (fit.right - root.width / 2f) * k, cy + (fit.bottom - root.height / 2f) * k)
            content.visibility = View.INVISIBLE
            morph(bmp, now to now, covering(fit, target) to target, 0, 260, finishNow)
        } else {
            content.animate().alpha(0f).scaleX(content.scaleX * .92f).scaleY(content.scaleY * .92f).setDuration(200).setInterpolator(standard).start()
            fadeBg(0, 200, finishNow)
        }
    }

    @Deprecated("Activity API")
    override fun onBackPressed() = close()

    override fun onUnlocked() {
        val edited = Session.edited ?: return
        Session.edited = null
        if (items.isEmpty()) return
        if (edited != current()) { items.add(pos + 1, edited); pos++ } // saved as a copy: show the copy
        show()
    }

    override fun onPause() { super.onPause(); savePosition(); video?.pause() }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null); loader.shutdownNow() }


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
        if (slideshow) { handler.removeCallbacks(nextSlide); handler.postDelayed(nextSlide, Prefs.slideSeconds * 1000L) }
        preload()
    }

    private fun preload() {
        val around = (pos - 1..pos + 1).filter { it in items.indices }.map { items[it] }
        previews.keys.retainAll(around.map { it.path }.toSet())
        val side = max(root.width, root.height).takeIf { it > 0 } ?: 2400
        around.filter { it.path !in previews && kindOf(it.name) != Kind.VIDEO }.forEach { f ->
            loader.execute {
                val b = runCatching { Images.decode(f, side) }.getOrNull() ?: return@execute
                runOnUiThread {
                    val i = items.indexOf(f)
                    if (i < pos - 1 || i > pos + 1) return@runOnUiThread
                    previews[f.path] = b
                    neighbor?.takeIf { it.tag == i }?.setImageBitmap(b)
                }
            }
        }
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
        (previews[f.path] ?: Thumbs.cached(f))?.let(v::setImageBitmap) // instant preview while the full image decodes
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
            if (Prefs.autoplay || slideshow) startVideo()
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
            if (slideshow) "Arrêter le minuteur" to ::stopSlideshow else "Diaporama" to { startSlideshow() },
            "Paramètres" to { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        PopupMenu(this, anchor).apply {
            actions.forEachIndexed { i, a -> menu.add(0, i, i, a.first) }
            setOnMenuItemClickListener { actions[it.itemId].second(); true }
            show()
        }
    }

    private fun startSlideshow(loop: Boolean = false) {
        slideshow = true; loopSlides = loop
        if (barsVisible) toggleBars()
        handler.removeCallbacks(nextSlide)
        handler.postDelayed(nextSlide, Prefs.slideSeconds * 1000L)
        if (video != null) startVideo()
    }

    private fun stopSlideshow() {
        slideshow = false; loopSlides = false
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
                duration = 250; interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
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
