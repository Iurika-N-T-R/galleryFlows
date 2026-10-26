package com.galleryflows

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decoding, saving and transforming full images (rotation, resize, editor output, print, GPS). */
object Images {
    private val ORIENT_TO_DEG = mapOf(1 to 0, 6 to 90, 3 to 180, 8 to 270)
    private val DEG_TO_ORIENT = ORIENT_TO_DEG.entries.associate { (k, v) -> v to k }
    private val KEPT_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    // Biggest side we decode for editing: bigger photos get downsampled to fit in memory
    val maxSide get() = if (Runtime.getRuntime().maxMemory() > 400L shl 20) 6144 else 4096

    private fun ext(f: File) = f.extension.lowercase()
    private fun isJpeg(f: File) = ext(f) == "jpg" || ext(f) == "jpeg"

    /** Formats we can write back in place; others are saved as a JPEG copy. */
    fun writable(f: File) = ext(f) in setOf("jpg", "jpeg", "png", "webp")

    /** Upright (EXIF applied), software bitmap. */
    fun decode(f: File, limit: Int = maxSide): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val side = max(info.size.width, info.size.height)
        d.setTargetSampleSize((side + limit - 1) / limit)
    }

    /** Where an edit of [f] goes when saved as a copy. */
    fun copyTarget(f: File, suffix: String): File {
        val ext = if (writable(f)) f.extension else "jpg"
        return Ops.uniqueTarget(f.parentFile!!, "${f.nameWithoutExtension}_$suffix.$ext")
    }

    /** Writes [bmp] to [target] (format from its extension) through a temp file, keeping the source's EXIF dates and GPS. */
    fun save(bmp: Bitmap, target: File, exifFrom: File?) {
        val tags = exifFrom?.takeIf(::isJpeg)?.let { src -> runCatching { ExifInterface(src.path).let { e -> KEPT_TAGS.associateWith { e.getAttribute(it) } } }.getOrNull() }
        val format = when (ext(target)) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.outputStream().use { check(bmp.compress(format, 95, it)) { "compress" } }
        if (tags != null && isJpeg(target)) runCatching {
            ExifInterface(tmp.path).apply {
                tags.forEach { (k, v) -> if (v != null) setAttribute(k, v) }
                setAttribute(ExifInterface.TAG_ORIENTATION, "1") // pixels are already upright
                saveAttributes()
            }
        }
        if (!tmp.renameTo(target)) { tmp.delete(); error("rename") }
    }

    fun transform(bmp: Bitmap, degrees: Int, flipX: Boolean = false, flipY: Boolean = false): Bitmap {
        if (degrees % 360 == 0 && !flipX && !flipY) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()); postScale(if (flipX) -1f else 1f, if (flipY) -1f else 1f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** JPEG: lossless, only the EXIF orientation changes. PNG/WebP: re-encoded. Others: not supported. */
    fun rotate(a: Activity, f: File, degrees: Int): Boolean {
        val mtime = f.lastModified()
        Thumbs.forget(f)
        val ok = runCatching {
            if (isJpeg(f)) ExifInterface(f.path).apply {
                val current = ORIENT_TO_DEG[getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)] ?: 0
                setAttribute(ExifInterface.TAG_ORIENTATION, DEG_TO_ORIENT.getValue((current + degrees + 360) % 360).toString())
                saveAttributes()
            } else if (writable(f)) save(transform(decode(f), degrees), f, null)
            else return false
        }.isSuccess
        if (ok && Prefs.keepDates) f.setLastModified(mtime)
        Ops.rescan(a, listOf(f.path))
        return ok
    }

    fun resize(f: File, scale: Float): File {
        val src = decode(f)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val out = copyTarget(f, "${w}x$h")
        save(Bitmap.createScaledBitmap(src, w, h, true), out, f)
        return out
    }

    fun latLong(f: File): FloatArray? = runCatching { FloatArray(2).takeIf { ExifInterface(f.path).getLatLong(it) } }.getOrNull()

    fun showOnMap(a: Activity, f: File) {
        val ll = latLong(f) ?: return a.toast("Pas de position GPS dans ce fichier")
        val geo = "geo:${ll[0]},${ll[1]}?q=${ll[0]},${ll[1]}"
        runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo))) }.onFailure { a.toast("Aucune application de carte") }
    }

    fun print(a: Activity, f: File) = thread {
        val bmp = runCatching { decode(f, 3000) }.getOrNull()
        a.runOnUiThread {
            if (bmp == null) return@runOnUiThread a.toast("Impossible d'imprimer ce fichier")
            a.getSystemService(PrintManager::class.java).print(f.name, object : PrintDocumentAdapter() {
                private var attrs: PrintAttributes? = null

                override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                    attrs = new
                    cb.onLayoutFinished(PrintDocumentInfo.Builder(f.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO).setPageCount(1).build(), true)
                }

                override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
                    val doc = PrintedPdfDocument(a, attrs!!)
                    val page = doc.startPage(0)
                    val c = page.canvas
                    val s = min(c.width / bmp.width.toFloat(), c.height / bmp.height.toFloat())
                    c.translate((c.width - bmp.width * s) / 2, (c.height - bmp.height * s) / 2)
                    c.scale(s, s)
                    c.drawBitmap(bmp, 0f, 0f, null)
                    doc.finishPage(page)
                    FileOutputStream(dest.fileDescriptor).use { doc.writeTo(it) }
                    doc.close()
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }
            }, null)
        }
    }
}
