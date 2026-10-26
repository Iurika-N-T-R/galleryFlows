package com.galleryflows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Thumbnails: memory LRU in front of a disk cache in cacheDir/thumbs, keyed on path + mtime + size so edits invalidate. */
object Thumbs {
    private const val PX = 512
    private lateinit var dir: File
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun init(c: Context) { dir = File(c.cacheDir, "thumbs").apply { mkdirs() } }

    private fun key(f: File) = "${f.path}|${f.lastModified()}|${f.length()}"

    private fun disk(key: String) = File(dir, MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })

    fun cached(f: File): Bitmap? = mem.get(key(f))

    /** Call before changing a file in place. */
    fun forget(f: File) { val k = key(f); mem.remove(k); disk(k).delete() }

    fun size() = dir.listFiles().orEmpty().sumOf { it.length() }

    fun clear() { mem.evictAll(); dir.listFiles()?.forEach { it.delete() } }

    fun load(view: ImageView, file: File?) {
        val k = file?.let(::key)
        view.tag = k
        val hit = k?.let { mem.get(it) }
        view.setImageBitmap(hit)
        if (file == null || hit != null) return
        pool.execute {
            if (view.tag != k) return@execute // scrolled away before its turn
            val bmp = get(file, k!!) ?: return@execute
            main.post { if (view.tag == k) view.setImageBitmap(bmp) }
        }
    }

    private fun get(file: File, k: String): Bitmap? {
        mem.get(k)?.let { return it }
        val d = disk(k)
        val bmp = BitmapFactory.decodeFile(d.path) ?: runCatching {
            val size = Size(PX, PX)
            if (kindOf(file.name) == Kind.VIDEO) ThumbnailUtils.createVideoThumbnail(file, size, null)
            else ThumbnailUtils.createImageThumbnail(file, size, null)
        }.getOrNull()?.also { b -> runCatching { d.outputStream().use { b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) } } }
        return bmp?.also { mem.put(k, it) }
    }
}
