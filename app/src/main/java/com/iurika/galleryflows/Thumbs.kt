package com.iurika.galleryflows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
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
            val (bmp, fresh) = get(file, k!!) ?: return@execute
            main.post {
                if (view.tag != k) return@post
                // a thumbnail that had to be made fades in; one from the disk cache is there at once, fading it would only flicker
                if (!fresh) view.setImageBitmap(bmp)
                else view.setImageDrawable(TransitionDrawable(arrayOf(ColorDrawable(0), BitmapDrawable(view.resources, bmp))).apply {
                    isCrossFadeEnabled = true; startTransition(150)
                })
            }
        }
    }

    /** The thumbnail, and whether it had to be made from the file. */
    private fun get(file: File, k: String): Pair<Bitmap, Boolean>? {
        mem.get(k)?.let { return it to false }
        val d = disk(k)
        BitmapFactory.decodeFile(d.path)?.let { mem.put(k, it); return it to false }
        val bmp = runCatching {
            val size = Size(PX, PX)
            if (kindOf(file.name) == Kind.VIDEO) ThumbnailUtils.createVideoThumbnail(file, size, null)
            else ThumbnailUtils.createImageThumbnail(file, size, null)
        }.getOrNull() ?: return null
        runCatching { d.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) } }
        mem.put(k, bmp)
        return bmp to true
    }
}
