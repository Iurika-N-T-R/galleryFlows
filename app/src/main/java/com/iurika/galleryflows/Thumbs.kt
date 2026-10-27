package com.iurika.galleryflows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Thumbnails: memory LRU in front of a disk store in filesDir/thumbs, keyed on path + mtime + size so edits invalidate.
 * Not cacheDir: Android empties an app's cache as soon as storage runs low, and 300 MB of thumbnails is always over its quota.
 */
object Thumbs {
    private const val MAX_BYTES = 1L shl 30 // the oldest-used go past 1 GB
    private const val DAY = 24 * 3600 * 1000L
    private lateinit var dir: File
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val px get() = when (Prefs.loadPriority) { 0 -> 256; 2 -> 768; else -> 512 }

    fun init(c: Context) {
        dir = File(c.filesDir, "thumbs").apply { mkdirs() }
        pool.execute {
            // 0.3 and before kept them in cacheDir: moved one by one, renaming that folder would keep its cache group
            File(c.cacheDir, "thumbs").let { old -> old.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }; old.delete() }
            trim()
        }
    }

    // ponytail: one listing at launch, fine up to ~100k thumbnails
    private fun trim() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES * 8 / 10) break
            total -= f.length(); f.delete()
        }
    }

    // 512 px keeps the key of the thumbnails made before the size became a setting
    private fun key(f: File) = "${f.path}|${f.lastModified()}|${f.length()}" + if (px != 512) "|$px" else ""

    private fun disk(key: String) = File(dir, MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })

    fun cached(f: File): Bitmap? = mem.get(key(f))

    /** Call before changing a file in place. */
    fun forget(f: File) { val k = key(f); mem.remove(k); disk(k).delete() }

    fun size() = dir.listFiles().orEmpty().sumOf { it.length() }

    fun clear() { mem.evictAll(); dir.listFiles()?.forEach { it.delete() } }

    /** Shows [file]'s thumbnail in [view]; [animate] then swaps in the moving GIF. */
    fun load(view: ImageView, file: File?, animate: Boolean = false) {
        val k = file?.let(::key)
        view.tag = k
        val hit = k?.let { mem.get(it) }
        view.setImageBitmap(hit)
        if (file == null) return
        if (hit != null) { if (animate) animated(view, file, k); return }
        pool.execute {
            if (view.tag != k) return@execute // scrolled away before its turn
            val (bmp, fresh) = get(file, k!!) ?: return@execute
            main.post {
                if (view.tag != k) return@post
                // a thumbnail that had to be made fades in; one from the disk store is there at once, fading it would only flicker
                if (!fresh) view.setImageBitmap(bmp)
                else view.setImageDrawable(TransitionDrawable(arrayOf(ColorDrawable(0), BitmapDrawable(view.resources, bmp))).apply {
                    isCrossFadeEnabled = true; startTransition(150)
                })
                if (animate) animated(view, file, k)
            }
        }
    }

    private fun animated(view: ImageView, file: File, k: String) = pool.execute {
        if (view.tag != k) return@execute
        val d = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { dec, info, _ ->
                dec.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 256))
            }
        }.getOrNull() as? AnimatedImageDrawable ?: return@execute
        main.post { if (view.tag == k) { view.setImageDrawable(d); d.start() } }
    }

    /** The thumbnail, and whether it had to be made from the file. */
    private fun get(file: File, k: String): Pair<Bitmap, Boolean>? {
        mem.get(k)?.let { return it to false }
        if (kindOf(file.name) == Kind.SVG) return svg().also { mem.put(k, it) } to false
        val d = disk(k)
        BitmapFactory.decodeFile(d.path)?.let {
            // last use, for trim(); once a day is enough
            val now = System.currentTimeMillis()
            if (now - d.lastModified() > DAY) d.setLastModified(now)
            mem.put(k, it); return it to false
        }
        val size = Size(px, px)
        val bmp = runCatching {
            if (kindOf(file.name) == Kind.VIDEO) ThumbnailUtils.createVideoThumbnail(file, size, null)
            else ThumbnailUtils.createImageThumbnail(file, size, null)
        }.getOrNull()
            ?: runCatching { ExifInterface(file.path).thumbnailBitmap }.getOrNull() // RAW the system can't decode: its embedded preview
            ?: return null
        runCatching { d.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) } }
        mem.put(k, bmp)
        return bmp to true
    }

    // ponytail: no SVG decoder on Android, so a labelled tile in the grid; the viewer renders the real drawing
    private fun svg(): Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(0xff262626.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY; textSize = 56f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("SVG", 128f, 128f - (p.ascent() + p.descent()) / 2, p)
    }
}
