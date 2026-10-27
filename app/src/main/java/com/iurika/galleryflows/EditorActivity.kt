package com.iurika.galleryflows

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Built-in editor: crop (free or fixed ratio), rotate, flip, brightness / contrast / saturation. */
class EditorActivity : Base() {
    private lateinit var file: File
    private lateinit var crop: CropView
    private lateinit var adjustPanel: LinearLayout
    private var source: Bitmap? = null
    private var brightness = 0f // -100..100
    private var contrast = 1f   // 0.5..1.5
    private var saturation = 1f // 0..2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra("path") ?: return finish())
        crop = CropView(this)
        val title = TextView(this).apply { text = "Modifier"; setTextColor(WHITE); textSize = 18f }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_close) { finish() })
            addView(title, LP(0, -2, 1f))
            addView(icon(R.drawable.ic_check) { saveDialog() })
        }
        val ratios = LinearLayout(this).apply {
            listOf("Libre" to 0f, "Original" to -1f, "1:1" to 1f, "4:3" to 4 / 3f, "3:4" to 3 / 4f, "16:9" to 16 / 9f, "9:16" to 9 / 16f).forEach { (label, r) ->
                addView(TextView(this@EditorActivity).apply {
                    text = label; setTextColor(WHITE); textSize = 14f; background = rounded(0xff333333.toInt(), 16)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { crop.setRatio(if (r < 0) crop.bitmapRatio() else r) }
                }, LP(-2, -2).apply { marginEnd = dp(8) })
            }
        }
        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(icon(R.drawable.ic_rotate) { transform(270) }.apply { scaleX = -1f }, LP(0, dp(56), 1f))
            addView(icon(R.drawable.ic_rotate) { transform(90) }, LP(0, dp(56), 1f))
            addView(icon(R.drawable.ic_flip) { transform(0, flipX = true) }, LP(0, dp(56), 1f))
            addView(icon(R.drawable.ic_flip) { transform(0, flipY = true) }.apply { rotation = 90f }, LP(0, dp(56), 1f))
            addView(icon(R.drawable.ic_tune) { adjustPanel.visibility = if (adjustPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }, LP(0, dp(56), 1f))
            addView(icon(R.drawable.ic_restore) { reset() }, LP(0, dp(56), 1f))
        }
        adjustPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), 0, dp(16), 0)
            addView(slider("Luminosité", 100) { brightness = (it - 100).toFloat() })
            addView(slider("Contraste", 100) { contrast = 0.5f + it / 200f })
            addView(slider("Saturation", 100) { saturation = it / 100f })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff000000.toInt())
            addView(top)
            addView(crop, LP(-1, 0, 1f))
            addView(adjustPanel)
            addView(HorizontalScrollView(this@EditorActivity).apply {
                isHorizontalScrollBarEnabled = false; setPadding(dp(12), dp(8), dp(12), dp(4)); addView(ratios)
            })
            addView(tools)
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); ins
            }
        })
        thread {
            val bmp = runCatching { Images.decode(file) }.getOrNull()
            runOnUiThread {
                if (bmp == null) { toast("Impossible d'ouvrir l'image"); finish(); return@runOnUiThread }
                source = bmp; crop.bitmap = bmp
            }
        }
    }

    private fun slider(label: String, start: Int, onChange: (Int) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@EditorActivity).apply { text = label; setTextColor(WHITE); textSize = 13f }, LP(dp(90), -2))
        addView(SeekBar(this@EditorActivity).apply {
            max = 200; progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { onChange(p); crop.filter = colorFilter() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, LP(0, -2, 1f))
    }

    private fun colorMatrix() = ColorMatrix().apply {
        setSaturation(saturation)
        val t = (1 - contrast) * 128 + brightness
        postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    }

    private fun adjusted() = brightness != 0f || contrast != 1f || saturation != 1f

    private fun colorFilter() = if (adjusted()) ColorMatrixColorFilter(colorMatrix()) else null

    private fun transform(degrees: Int, flipX: Boolean = false, flipY: Boolean = false) {
        val b = crop.bitmap ?: return
        crop.bitmap = Images.transform(b, degrees, flipX, flipY)
    }

    private fun reset() {
        brightness = 0f; contrast = 1f; saturation = 1f
        for (i in 0 until adjustPanel.childCount) ((adjustPanel.getChildAt(i) as LinearLayout).getChildAt(1) as SeekBar).progress = 100
        crop.filter = null
        crop.bitmap = source
    }

    private fun saveDialog() {
        if (crop.bitmap == null) return
        val options = listOfNotNull(
            if (Images.writable(file)) "Remplacer l'original" to { save(file) } else null,
            "Enregistrer une copie" to { save(Images.copyTarget(file, "modifié")) },
        )
        AlertDialog.Builder(this).setTitle("Enregistrer")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }.show()
    }

    private fun save(target: File) {
        val b = crop.bitmap ?: return
        val r = crop.crop
        val filter = colorFilter()
        toast("Enregistrement…")
        thread {
            val ok = runCatching {
                var out = Bitmap.createBitmap(b, r.left.roundToInt(), r.top.roundToInt(), r.width().roundToInt().coerceAtLeast(1), r.height().roundToInt().coerceAtLeast(1))
                if (filter != null) out = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).drawBitmap(out, 0f, 0f, Paint().apply { colorFilter = filter })
                }
                val mtime = file.lastModified()
                if (target == file) Thumbs.forget(file)
                Images.save(out, target, file)
                if (target == file && Prefs.keepDates) target.setLastModified(mtime)
            }.isSuccess
            runOnUiThread {
                if (!ok) return@runOnUiThread toast("Impossible d'enregistrer")
                Ops.rescan(this, listOf(target.path))
                Session.dirty = true
                Session.edited = target
                toast("Enregistré : ${target.name}")
                finish()
            }
        }
    }
}

/** Shows a bitmap fitted on screen with a draggable crop rectangle (corners resize, inside moves). */
class CropView(c: Context) : View(c) {
    var bitmap: Bitmap? = null
        set(v) { field = v; ratio = 0f; crop = v?.let { RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) } ?: RectF(); invalidate() }
    var crop = RectF() // in bitmap pixels
        private set
    var filter: android.graphics.ColorFilter? = null
        set(v) { field = v; paint.colorFilter = v; invalidate() }
    private var ratio = 0f // width / height, 0 = free
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0xaa000000.toInt() }
    private val line = Paint().apply { color = WHITE; style = Paint.Style.STROKE; strokeWidth = c.dp(2).toFloat() }
    private val grid = Paint().apply { color = 0x88ffffff.toInt(); strokeWidth = 1f }
    private val handle = Paint().apply { color = WHITE }
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var dragging = -1 // 0..3 corners, 4 = move, -1 none
    private var lastX = 0f
    private var lastY = 0f

    fun bitmapRatio() = bitmap?.let { it.width / it.height.toFloat() } ?: 0f

    fun setRatio(r: Float) {
        val b = bitmap ?: return
        ratio = r
        if (r <= 0f) return invalidate()
        val w = min(b.width.toFloat(), b.height * r)
        val h = w / r
        crop = RectF((b.width - w) / 2, (b.height - h) / 2, (b.width + w) / 2, (b.height + h) / 2)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val pad = context.dp(24).toFloat()
        scale = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        ox = (width - b.width * scale) / 2
        oy = (height - b.height * scale) / 2
        canvas.drawBitmap(b, null, RectF(ox, oy, ox + b.width * scale, oy + b.height * scale), paint)
        val r = RectF(ox + crop.left * scale, oy + crop.top * scale, ox + crop.right * scale, oy + crop.bottom * scale)
        canvas.drawRect(0f, 0f, width.toFloat(), r.top, dim)
        canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, dim)
        for (i in 1..2) {
            canvas.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, grid)
            canvas.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, grid)
        }
        canvas.drawRect(r, line)
        val hs = context.dp(7).toFloat()
        corners(r).forEach { (x, y) -> canvas.drawCircle(x, y, hs, handle) }
    }

    private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return false
        val x = (e.x - ox) / scale
        val y = (e.y - oy) / scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val reach = context.dp(28) / scale
                dragging = corners(crop).indexOfFirst { (cx, cy) -> abs(cx - x) < reach && abs(cy - y) < reach }
                if (dragging < 0 && crop.contains(x, y)) dragging = 4
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val minSize = 32f
                val bw = b.width.toFloat()
                val bh = b.height.toFloat()
                when (dragging) {
                    4 -> {
                        val dx = (x - lastX).coerceIn(-crop.left, bw - crop.right)
                        val dy = (y - lastY).coerceIn(-crop.top, bh - crop.bottom)
                        crop.offset(dx, dy)
                    }
                    in 0..3 -> {
                        // the opposite corner stays put
                        val ax = if (dragging % 2 == 0) crop.right else crop.left
                        val ay = if (dragging < 2) crop.bottom else crop.top
                        var px = x.coerceIn(0f, bw)
                        var py = y.coerceIn(0f, bh)
                        if (ratio > 0f) {
                            var w = abs(px - ax)
                            val maxH = if (dragging < 2) ay else bh - ay
                            w = min(w, maxH * ratio)
                            px = if (px < ax) ax - w else ax + w
                            py = if (dragging < 2) ay - w / ratio else ay + w / ratio
                        }
                        val r = RectF(min(ax, px), min(ay, py), maxOf(ax, px), maxOf(ay, py))
                        if (r.width() >= minSize && r.height() >= minSize) crop = r
                    }
                }
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        return true
    }
}
