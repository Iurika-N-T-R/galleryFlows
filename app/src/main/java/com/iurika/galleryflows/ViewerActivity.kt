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
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.WindowManager
import android.webkit.WebView
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
import kotlin.math.atan2
import kotlin.math.hypot
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
    private var star: ImageView? = null // the favorite button, when the bar has it
    private var brightFrom = 0f // screen brightness when a brightness drag started
    private var opaqueNow = false // turned opaque to force an orientation: no grid behind to shrink back into
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
    private val bg by lazy { ColorDrawable(if (Prefs.blackBg) 0xff000000.toInt() else BG) }
    private val standard = PathInterpolator(.2f, 0f, 0f, 1f) // Material standard easing
    private var animating = false
    private var closing = false
    // Screen-sized decodes of the media around the current one, so a swipe shows them sharp at once
    private val previews = HashMap<String, Bitmap>()
    private val loader = Executors.newSingleThreadExecutor()
    private var neighbor: ImageView? = null // the page coming in beside the current one during a swipe
    private val gap get() = dp(16)

    // Touch: 0 undecided, 1 swiping pages, 2 dragging down to close, 3 brightness, -1 left to the views (zoom, pan, pinch, bars)
    private var dragMode = 0
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val hideHint = Runnable { hint.visibility = View.GONE }
    private var shownPlaying: Boolean? = null
    private val tick = object : Runnable {
        override fun run() {
            val v = video ?: return
            if (v.duration > 0) {
                seek.max = v.duration
                if (!seek.isPressed) seek.progress = v.currentPosition
                timeNow.text = DateUtils.formatElapsedTime(v.currentPosition / 1000L)
                timeEnd.text = DateUtils.formatElapsedTime(v.duration / 1000L)
            }
            // only on a change: setting the icon inflates it again and lays the whole window out, every 250 ms, under the video
            if (v.isPlaying != shownPlaying) { shownPlaying = v.isPlaying; playPause.setImageResource(if (v.isPlaying) R.drawable.ic_pause else R.drawable.ic_play) }
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
        window.attributes = window.attributes.apply {
            if (Prefs.maxBrightness) screenBrightness = 1f
            // always the whole screen: in the default mode the window dodges the camera cutout only while the status bar is
            // hidden, so hiding the bars shrank it and the video jumped; "Afficher une encoche" is a fixed padding instead
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        content = FrameLayout(this)
        title = TextView(this).apply { setTextColor(WHITE); textSize = 18f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = shade(GradientDrawable.Orientation.TOP_BOTTOM)
            addView(icon(R.drawable.ic_back) { close() })
            addView(title, LP(0, -2, 1f))
            addView(icon(R.drawable.ic_more) { menu(it) })
        }
        val buttons = if (trashMode) listOf(
            icon(R.drawable.ic_restore) { restore() },
            icon(R.drawable.ic_delete) { delete() },
            icon(R.drawable.ic_info) { info() },
        ) else if (!Prefs.showActions) emptyList()
        else Prefs.actions.mapNotNull { k -> VIEWER_ACTIONS.find { it.first == k } }.map { (k, label, res) ->
            icon(res) { act(k) }.apply { contentDescription = t(label); if (k == "favorite") star = this }
        }
        timeNow = TextView(this).apply { setTextColor(WHITE); textSize = 13f; gravity = Gravity.CENTER; setSingleLine() }
        timeEnd = TextView(this).apply { setTextColor(WHITE); textSize = 13f; gravity = Gravity.CENTER; setSingleLine() }
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
            addView(timeNow, LP(dp(52), -2)) // fixed widths: the ticking times don't relayout the row
            addView(seek, LP(0, -2, 1f))
            addView(timeEnd, LP(dp(52), -2))
        }
        details = TextView(this).apply { setTextColor(WHITE); textSize = 13f; setShadowLayer(4f, 0f, 0f, 0xff000000.toInt()); setPadding(dp(16), 0, dp(16), dp(4)) }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shade(GradientDrawable.Orientation.BOTTOM_TOP)
            addView(details)
            addView(videoRow)
            if (buttons.isNotEmpty()) addView(LinearLayout(this@ViewerActivity).apply { buttons.forEach { addView(it, LP(0, dp(56), 1f)) } })
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
                // ignoring visibility: hiding the system bars doesn't move the app's bars (no jump when they come back)
                val b = ins.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                val c = ins.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                if (Prefs.showNotch) content.setPadding(c.left, c.top, c.right, c.bottom) else content.setPadding(0, 0, 0, 0)
                topBar.setPadding(b.left, b.top, b.right, dp(8))
                bottomBar.setPadding(b.left, dp(8), b.right, b.bottom)
                ins
            }
        }
        setContentView(root)
        // drawn behind the system bars at all times: hiding them doesn't resize the window, so the video doesn't jump
        window.setDecorFitsSystemWindows(false)
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
            val vertical = abs(dy) > slop && abs(dy) > abs(dx)
            if (abs(dx) > slop && abs(dx) > abs(dy)) dragMode = 1
            // brightness on the left half; down on the right half still closes
            else if (vertical && Prefs.imageBrightness && video == null && downX < root.width / 2f) { dragMode = 3; brightFrom = brightness() }
            else if (vertical && dy > 0 && Prefs.swipeDownClose && !videoOwnsVertical) dragMode = 2
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
            MotionEvent.ACTION_MOVE -> when (dragMode) { 1 -> dragPage(dx); 2 -> dragDown(dx, dy); else -> setBrightness(brightFrom - dy / root.height) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val v = velocity!!.apply { computeCurrentVelocity(1000) }
                val fast = dp(600)
                if (dragMode == 3) Unit
                else if (dragMode == 1) {
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
        // inside content's padding (the notch), like the picture itself
        val l = content.paddingLeft.toFloat(); val t = content.paddingTop.toFloat()
        val w = root.width - l - content.paddingRight; val h = root.height - t - content.paddingBottom
        val s = min(w / bmp.width, h / bmp.height)
        return RectF((w - bmp.width * s) / 2, (h - bmp.height * s) / 2, (w + bmp.width * s) / 2, (h + bmp.height * s) / 2).apply { offset(l, t) }
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
        // only back into the grid it came from, not into a browser left open behind an "Open with", nor one hidden behind an opaque window
        val target = Session.locate?.takeIf { intent.data == null && !opaqueNow }?.invoke(f)?.let(::windowToRoot)
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
        renderStar()
        renderDetails(f)
        when {
            kindOf(f.name) == Kind.VIDEO && !Prefs.externalPlayer -> showVideo(f)
            kindOf(f.name) == Kind.SVG -> showSvg(f)
            else -> showImage(f)
        }
        if (Prefs.orientation != 3) orient(0, 0)
        if (slideshow) { handler.removeCallbacks(nextSlide); handler.postDelayed(nextSlide, Prefs.slideSeconds * 1000L) }
        preload()
    }

    private fun preload() {
        val around = (pos - 1..pos + 1).filter { it in items.indices }.map { items[it] }
        previews.keys.retainAll(around.map { it.path }.toSet())
        val side = max(root.width, root.height).takeIf { it > 0 } ?: 2400
        around.filter { it.path !in previews && kindOf(it.name) != Kind.VIDEO && kindOf(it.name) != Kind.SVG }.forEach { f ->
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
            formatDate(f.lastModified()),
        ).joinToString("  ·  ")
    }

    private fun showImage(f: File) {
        val v = ZoomImageView(this).apply { onTap = ::tapped }
        if (kindOf(f.name) != Kind.GIF) v.source = f
        image = v
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        (previews[f.path] ?: Thumbs.cached(f))?.let(v::setImageBitmap) // instant preview while the full image decodes
        if (kindOf(f.name) == Kind.VIDEO) { // external player mode: poster + play button
            thread {
                val poster = runCatching { ThumbnailUtils.createVideoThumbnail(f, Size(1280, 1280), null) }.getOrNull()
                runOnUiThread { if (image === v && poster != null) v.setImageBitmap(poster) }
            }
            content.addView(playButton { openWith(f, Intent.ACTION_VIEW, t("Lire avec")) }, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            return
        }
        thread {
            val size = Images.uprightSize(f)
            val d: Drawable? = runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    // keep the longest side under 4096 px: bigger bitmaps can't be drawn (zooming in brings sharp tiles)
                    val side = max(info.size.width, info.size.height)
                    dec.setTargetSampleSize((side + 4095) / 4096)
                }
            }.getOrNull()
                // a RAW the system can't decode: its embedded preview
                ?: runCatching { ExifInterface(f.path).thumbnailBitmap?.let { BitmapDrawable(resources, it) } }.getOrNull()
            runOnUiThread {
                if (image !== v) return@runOnUiThread
                v.fullW = size.first; v.fullH = size.second
                if (d == null) toast(t("Impossible d'ouvrir %s", f.name))
                else { v.setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() }
                if (Prefs.orientation == 3) orient(size.first, size.second)
            }
        }
    }

    // No SVG decoder on Android: the system WebView draws it, with its own pinch zoom
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showSvg(f: File) {
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { tapped(e.x); return true }
        })
        content.addView(WebView(this).apply {
            setBackgroundColor(0)
            settings.allowFileAccess = true
            settings.builtInZoomControls = true; settings.displayZoomControls = false
            val page = "<html><body style='margin:0;height:100vh;display:flex;align-items:center;justify-content:center'>" +
                "<img src='${Uri.fromFile(f)}' style='max-width:100%;max-height:100%'></body></html>"
            loadDataWithBaseURL("file:///", page, "text/html", "utf-8", null)
            setOnTouchListener { _, e -> taps.onTouchEvent(e); false }
        }, FrameLayout.LayoutParams(-1, -1))
    }

    /** "Orientation": [w]×[h] is the media's upright size, for "selon le média". */
    private fun orient(w: Int, h: Int) {
        val wanted = when (Prefs.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            3 -> if (w <= 0 || h <= 0) return else if (w > h) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation == wanted) return
        // a see-through window takes the orientation of the one behind it: forcing one needs an opaque window
        if (wanted != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED && !opaqueNow) opaqueNow = setTranslucent(false)
        requestedOrientation = wanted
    }

    private fun orientationDialog() {
        val labels = listOf("Utiliser l'orientation du système", "Forcer le portrait", "Forcer le paysage", "Selon l'orientation du média")
        AlertDialog.Builder(this).setTitle(t("Orientation"))
            .setSingleChoiceItems(labels.map { t(it) }.toTypedArray(), Prefs.orientation) { d, i ->
                d.dismiss(); Prefs.orientation = i; show()
            }.show()
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
            if (Prefs.orientation == 3) orient(mp.videoWidth, mp.videoHeight)
            v.seekTo(max(1, if (Prefs.rememberPosition) Prefs.position(f) else 0)) // a frame instead of black
            if (Prefs.autoplay || slideshow) startVideo()
        }
        v.setOnCompletionListener { playOverlay.visibility = View.VISIBLE; Prefs.savePosition(f, 0) }
        v.setOnErrorListener { _, _, _ -> toast(t("Lecture impossible, essaie « Ouvrir avec »")); true }
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
                if (e1.x < width / 2) setBrightness(brightness() + dy / height)
                else {
                    pending += dy
                    val stepPx = dp(30)
                    while (abs(pending) >= stepPx) {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (pending > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                        pending -= if (pending > 0) stepPx else -stepPx
                    }
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    flash(t("Volume %d %%", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max))
                }
                return true
            }
        })
        setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    private fun brightness() = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
        flash(t("Luminosité %d %%", (brightness() * 100).roundToInt()))
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
        // a fade, and INVISIBLE rather than GONE: nothing is laid out again under a playing video
        for (bar in listOf(topBar, bottomBar)) {
            bar.animate().cancel()
            if (barsVisible) { bar.visibility = View.VISIBLE; bar.animate().alpha(1f).setDuration(180).start() }
            else bar.animate().alpha(0f).setDuration(180).withEndAction { bar.visibility = View.INVISIBLE }.start()
        }
        window.insetsController?.let { if (barsVisible) it.show(WindowInsets.Type.systemBars()) else it.hide(WindowInsets.Type.systemBars()) }
    }

    // ---- actions ----

    private fun toggleFavorite() {
        val p = current().path
        Prefs.favorites = if (p in Prefs.favorites) Prefs.favorites - p else Prefs.favorites + p
        renderStar()
        star?.animate()?.scaleX(1.25f)?.scaleY(1.25f)?.setDuration(90)?.withEndAction { star?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start() }?.start()
        Session.dirty = true
    }

    private fun renderStar() {
        val on = current().path in Prefs.favorites
        star?.setImageResource(if (on) R.drawable.ic_star_fill else R.drawable.ic_star)
        star?.setColorFilter(if (on) ACCENT else WHITE)
    }

    private fun edit() {
        val f = current()
        if (isPhoto(f.name)) startActivity(Intent(this, EditorActivity::class.java).putExtra("path", f.path))
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
        else confirm(t(if (permanent) "Supprimer définitivement %s ?" else "Mettre %s à la corbeille ?", f.name), run)
    }

    private fun restore() {
        val f = current()
        toast(Trash.restore(this, listOf(f)))
        if (!f.exists()) removeCurrent()
    }

    private fun transfer(move: Boolean) {
        val f = current()
        pickDestination(move) { dest ->
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
                        items.add(pos + 1, out); toast(t("Enregistré : %s", out.name))
                    }
                }
                Unit
            }
        })
    }

    /** Runs one of VIEWER_ACTIONS. */
    private fun act(key: String) {
        val f = current()
        when (key) {
            "favorite" -> toggleFavorite()
            "edit" -> edit()
            "share" -> share(listOf(f))
            "delete" -> delete()
            "info" -> info()
            "rotate" -> rotate()
            "slideshow" -> if (slideshow) stopSlideshow() else startSlideshow()
            "copy" -> transfer(move = false)
            "move" -> transfer(move = true)
            "rename" -> ask(t("Renommer"), f.name, ::rename)
            "hide" -> if (Ops.rename(this, f, ".${f.name}") != null) removeCurrent() else toast(t("Impossible de cacher"))
            "open" -> openWith(f, Intent.ACTION_VIEW, t("Ouvrir avec"))
            "setas" -> openWith(f, Intent.ACTION_ATTACH_DATA, t("Définir en tant que"))
            "map" -> Images.showOnMap(this, f)
            "print" -> Images.print(this, f)
            "resize" -> resize()
            "shortcut" -> pinShortcut(f.path, f.name, Thumbs.cached(f),
                Intent(this, ViewerActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Files.uri(f)))
            "orientation" -> orientationDialog()
        }
    }

    // ponytail: map, print, rotate and resize are for photos only; the rest works on everything
    private fun applies(key: String, f: File) = key !in setOf("rotate", "resize", "map", "print") || kindOf(f.name).let { it != Kind.VIDEO && it != Kind.SVG }

    private fun menu(anchor: View) {
        val f = current()
        val actions = if (trashMode) listOf<Pair<String, () -> Unit>>(
            t("Restaurer") to ::restore,
            t("Supprimer définitivement") to ::delete,
            t("Ouvrir avec") to { openWith(f, Intent.ACTION_VIEW, t("Ouvrir avec")) },
        ) else {
            // what the bottom bar doesn't already show
            val inBar = if (Prefs.showActions) Prefs.actions.toSet() else emptySet()
            VIEWER_ACTIONS.filter { (k) -> k !in inBar && applies(k, f) }.map { (k, label) ->
                val text = when {
                    k == "favorite" -> if (f.path in Prefs.favorites) "Retirer des favoris" else "Ajouter aux favoris"
                    k == "slideshow" && slideshow -> "Arrêter le minuteur"
                    else -> label
                }
                t(text) to { act(k) }
            } + listOf(
                t("Modifier avec…") to { openWith(f, Intent.ACTION_EDIT, t("Modifier avec")) },
                t("Paramètres") to { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
        }
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
            t("Nom : %s", f.name),
            t("Dossier : %s", Trash.originalOf(f)?.let { File(it).parent } ?: f.parent),
            t("Taille : %s", Formatter.formatFileSize(this, f.length())),
            t("Modifié : %s", formatDate(f.lastModified())),
        )
        runCatching {
            if (kindOf(f.name) == Kind.VIDEO) MediaMetadataRetriever().use { r ->
                r.setDataSource(f.path)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                lines += t("Résolution : %s", "$w × $h")
                lines += t("Durée : %s", DateUtils.formatElapsedTime(ms / 1000))
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth > 0) lines += t("Résolution : %s", "${o.outWidth} × ${o.outHeight}")
                val e = ExifInterface(f.path)
                e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { lines += t("Prise de vue : %s", it) }
                listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL))
                    .joinToString(" ").takeIf { it.isNotBlank() }?.let { lines += t("Appareil : %s", it) }
                Images.latLong(f)?.let { lines += "GPS : %.5f, %.5f".format(it[0], it[1]) }
            }
        }
        AlertDialog.Builder(this).setTitle(t("Propriétés")).setMessage(lines.joinToString("\n\n")).setPositiveButton("OK", null).show()
    }
}

/**
 * ImageView with pinch zoom, double-tap zoom, panning and two-finger rotation. Zoomed past what the decoded picture holds,
 * it decodes the visible part of [source] at full resolution and draws that sharp tile over it.
 */
class ZoomImageView(c: Context) : ImageView(c) {
    var onTap: (Float) -> Unit = {}
    var source: File? = null
    var fullW = 0 // upright size of the file itself, the drawable may be downsampled
    var fullH = 0
    private val m = Matrix()
    private var scale = 1f // relative to "fit screen"
    private var turned = 0f // degrees, from the rotation gesture
    val isZoomed get() = scale > 1.01f

    private val sideways get() = (turned / 90).roundToInt() % 2 != 0
    // one file pixel per screen pixel, as a multiple of "fit screen"
    private val native: Float get() {
        if (fullW <= 0 || width == 0) return 1f
        val (w, h) = if (sideways) fullH to fullW else fullW to fullH
        return 1 / min(width / w.toFloat(), height / h.toFloat())
    }
    private val maxScale get() = if (Prefs.advancedZoom) max(8f, native * 4) else 4f

    private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomTo(scale * d.scaleFactor, d.focusX, d.focusY); return true }
    })
    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (isZoomed) { m.postTranslate(-dx, -dy); fix() }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = when {
                isZoomed -> 1f
                Prefs.oneToOne && native > 1.2f -> native
                else -> 2.5f
            }
            ValueAnimator.ofFloat(scale, target).apply {
                duration = 250; interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                addUpdateListener { zoomTo(it.animatedValue as Float, e.x, e.y) }
            }.start()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(e.x); return true }
    })

    init { scaleType = ScaleType.MATRIX }

    // ---- rotation gesture: follows the fingers past 15°, then settles on the nearest quarter turn ----

    private var lastAngle = Float.NaN
    private var spin = 0f
    private var rotating = false

    private fun rotateGesture(e: MotionEvent) {
        if (e.pointerCount < 2 || e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            if (rotating) settleTurn()
            lastAngle = Float.NaN; spin = 0f
            return
        }
        val a = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
        if (!lastAngle.isNaN()) {
            var d = a - lastAngle
            if (d > 180) d -= 360 else if (d < -180) d += 360
            spin += d
            if (!rotating && abs(spin) > 15) { rotating = true; d = spin }
            if (rotating) { m.postRotate(d, width / 2f, height / 2f); turned += d; imageMatrix = m }
        }
        lastAngle = a
    }

    private fun settleTurn() {
        rotating = false
        val target = (turned / 90).roundToInt() * 90f
        var done = turned
        ValueAnimator.ofFloat(turned, target).apply {
            duration = 200; interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { val v = it.animatedValue as Float; m.postRotate(v - done, width / 2f, height / 2f); done = v; imageMatrix = m }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { turned = target % 360; if (isZoomed) fix() else reset() }
            })
        }.start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (Prefs.gestureRotate) rotateGesture(e)
        return true
    }

    override fun setImageDrawable(d: Drawable?) { super.setImageDrawable(d); tile = null; reset() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); reset() }

    private fun reset() {
        val d = drawable ?: return
        if (width == 0 || d.intrinsicWidth <= 0) return
        val iw = d.intrinsicWidth.toFloat(); val ih = d.intrinsicHeight.toFloat()
        val (w, h) = if (sideways) ih to iw else iw to ih
        val s = min(width / w, height / h)
        m.setRotate(turned, iw / 2, ih / 2)
        m.postScale(s, s)
        val r = RectF(0f, 0f, iw, ih).also(m::mapRect)
        m.postTranslate(width / 2f - r.centerX(), height / 2f - r.centerY())
        scale = 1f
        imageMatrix = m
        sharpenLater()
    }

    private fun zoomTo(target: Float, x: Float, y: Float) {
        val f = target.coerceIn(1f, maxScale) / scale
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
        sharpenLater()
    }

    // ---- sharp tile ----

    private var tile: Bitmap? = null
    private val tileRect = RectF() // where it goes, in drawable pixels
    private var tileJob = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sharpen = Runnable { requestTile() }

    private fun sharpenLater() { removeCallbacks(sharpen); postDelayed(sharpen, 180) }

    private fun requestTile() {
        val f = source ?: return
        val d = drawable ?: return
        if (!Prefs.bestQuality || fullW <= 0 || width == 0) return
        val k = fullW.toFloat() / d.intrinsicWidth // file px per drawable px; both upright, the gesture turn is in m
        val v = FloatArray(9).also(m::getValues)
        val onScreen = hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]) // screen px per drawable px
        if (k < 1.3f || onScreen <= 1.05f) { tile = null; invalidate(); return } // the drawable already holds enough pixels
        val visible = RectF(0f, 0f, width.toFloat(), height.toFloat())
        Matrix().also { m.invert(it) }.mapRect(visible)
        if (!visible.intersect(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())) return
        var sample = 1
        while (onScreen / k * sample * 2 <= 1f) sample *= 2 // no more file pixels than screen pixels
        val job = ++tileJob
        val area = RectF(visible)
        TILES.execute {
            val bmp = runCatching { Images.region(f, RectF(area.left * k, area.top * k, area.right * k, area.bottom * k), sample) }.getOrNull()
            post { if (job == tileJob && bmp != null) { tile = bmp; tileRect.set(area); invalidate() } }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = tile ?: return
        canvas.save(); canvas.concat(m)
        canvas.drawBitmap(t, null, tileRect, paint)
        canvas.restore()
    }

    private companion object { val TILES: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor() }
}
