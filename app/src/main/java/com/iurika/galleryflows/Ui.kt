package com.iurika.galleryflows

import android.app.Activity
import android.app.AlertDialog
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.storage.StorageManager
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

// "Personnaliser les couleurs": index = Prefs.accent / Prefs.background; each accent has its Accent<i> style for the dialogs
val ACCENTS = listOf("Orange" to 0xfff57c00, "Bleu" to 0xff3d8bfd, "Vert" to 0xff43a047, "Violet" to 0xff9c5cf0,
    "Rose" to 0xffe8457c, "Rouge" to 0xffe53935, "Turquoise" to 0xff14a89a, "Jaune" to 0xfff2b705)
val BACKGROUNDS = listOf("Gris foncé" to 0xff1b1b1b, "Noir" to 0xff000000, "Ardoise" to 0xff161b22)
val ACCENT get() = ACCENTS.getOrElse(Prefs.accent) { ACCENTS[0] }.second.toInt()
val BG get() = BACKGROUNDS.getOrElse(Prefs.background) { BACKGROUNDS[0] }.second.toInt()
val BAR get() = blend(ACCENT, BG, .25f) // the search bar: a dark tint of the accent
const val GREY = 0xff9a9a9a.toInt()
const val WHITE = 0xffffffff.toInt()

/** [a] over [b] at [k] opacity. */
fun blend(a: Int, b: Int, k: Float): Int {
    fun ch(shift: Int) = ((a shr shift and 0xff) * k + (b shr shift and 0xff) * (1 - k)).toInt() shl shift
    return 0xff000000.toInt() or ch(16) or ch(8) or ch(0)
}

fun withAlpha(color: Int, alpha: Int) = (color and 0xffffff) or (alpha shl 24)

val DATE_FORMATS = listOf("dd.MM.yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "d MMMM yyyy", "MMMM d yyyy")

fun formatDate(t: Long, time: Boolean = true): String {
    val pattern = DATE_FORMATS.getOrElse(Prefs.dateFormat) { DATE_FORMATS[1] } + if (!time) "" else if (Prefs.hour24) " HH:mm" else " h:mm a"
    return SimpleDateFormat(pattern, if (Prefs.english) Locale.ENGLISH else Locale.FRENCH).format(Date(t))
}

/** The viewer's buttons: key, label, icon. Prefs.actions picks the bottom bar's; the ⋮ menu has them all. */
val VIEWER_ACTIONS = listOf(
    Triple("favorite", "Favori", R.drawable.ic_star), Triple("edit", "Modifier", R.drawable.ic_edit),
    Triple("share", "Partager", R.drawable.ic_share), Triple("delete", "Supprimer", R.drawable.ic_delete),
    Triple("info", "Propriétés", R.drawable.ic_info), Triple("rotate", "Pivoter", R.drawable.ic_rotate),
    Triple("slideshow", "Diaporama", R.drawable.ic_slideshow), Triple("copy", "Copier vers", R.drawable.ic_copy),
    Triple("move", "Déplacer vers", R.drawable.ic_move), Triple("rename", "Renommer", R.drawable.ic_rename),
    Triple("hide", "Cacher", R.drawable.ic_hide), Triple("open", "Ouvrir avec", R.drawable.ic_open),
    Triple("setas", "Définir en tant que", R.drawable.ic_image), Triple("map", "Afficher sur la carte", R.drawable.ic_map),
    Triple("print", "Imprimer", R.drawable.ic_print), Triple("resize", "Redimensionner", R.drawable.ic_resize),
    Triple("shortcut", "Créer un raccourci", R.drawable.ic_link), Triple("orientation", "Orientation", R.drawable.ic_phone),
)

typealias LP = LinearLayout.LayoutParams

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radius: Int) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) }

fun Context.toast(msg: String) = Toast.makeText(this, t(msg), Toast.LENGTH_SHORT).show()

fun Context.icon(res: Int, onClick: (View) -> Unit) = ImageView(this).apply {
    setImageResource(res)
    val p = dp(13)
    setPadding(p, p, p, p)
    background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, null)
    setOnClickListener(onClick)
}

/** Touch feedback; [mask] gives its shape, a plain rectangle otherwise. */
fun ripple(mask: Drawable = ColorDrawable(WHITE)) = RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, mask)

/** A square thumbnail: height follows width. */
class SquareImageView(c: Context) : ImageView(c) {
    init { scaleType = ScaleType.CENTER_CROP }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

fun Context.confirm(msg: String, onOk: () -> Unit) {
    AlertDialog.Builder(this).setMessage(t(msg))
        .setPositiveButton("OK") { _, _ -> onOk() }
        .setNegativeButton(t("Annuler"), null).show()
}

fun Context.ask(title: String, initial: String, onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        setText(initial); setSingleLine()
        setSelection(0, initial.substringBeforeLast('.').length) // select the name, not the extension
    }
    AlertDialog.Builder(this).setTitle(t(title))
        .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
        .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
        .setNegativeButton(t("Annuler"), null).show()
}

fun Context.choose(title: String, options: List<Pair<String, () -> Unit>>) {
    AlertDialog.Builder(this).setTitle(t(title))
        .setItems(options.map { t(it.first) }.toTypedArray()) { _, i -> options[i].second() }.show()
}

/** Folder browser dialog over every storage volume (internal + SD). */
fun Context.pickFolder(title: String, start: File?, onPick: (File) -> Unit) {
    val volumes = getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
    fun open(dir: File?) {
        val entries = if (dir == null) volumes
        else dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.sortedBy { it.name.lowercase() }
        val labels = (if (dir == null) emptyList() else listOf("⬆  " + t("Dossier parent"))) +
            entries.map { if (dir == null) it.path else "📁  ${it.name}" }
        val b = AlertDialog.Builder(this).setTitle(dir?.path ?: t(title))
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    dir == null -> open(entries[i])
                    i == 0 -> open(if (dir in volumes) null else dir.parentFile)
                    else -> open(entries[i - 1])
                }
            }
            .setNegativeButton(t("Annuler"), null)
        if (dir != null) b.setPositiveButton(t("Choisir ici")) { _, _ -> onPick(dir) }
            .setNeutralButton(t("Nouveau dossier")) { _, _ ->
                ask("Nouveau dossier", "") { name -> File(dir, name).let { if (it.mkdirs() || it.isDirectory) open(it) else toast("Création impossible") } }
            }
        b.show()
    }
    open(start)
}

fun Context.share(files: Collection<File>) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map(Files::uri))
    val types = files.map { mimeOf(it) }.distinct()
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    i.type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    i.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(i, t("Partager")))
}

/** Fingerprint / face / screen lock prompt. Without any screen lock set on the phone there is nothing to check against: passes. */
fun Activity.authenticate(title: String, onOk: () -> Unit, onFail: () -> Unit = {}) {
    val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (getSystemService(BiometricManager::class.java).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) return onOk()
    BiometricPrompt.Builder(this).setTitle(title).setAllowedAuthenticators(auth).build()
        .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onOk()
            override fun onAuthenticationError(code: Int, msg: CharSequence?) = onFail()
        })
}

/** Runs [action] after the "protéger suppression et déplacement" check, when that option is on. */
fun Activity.guardOps(action: () -> Unit) = if (Prefs.lockOps) authenticate(t("Confirmer l'opération"), action) else action()

/** Every screen: takes the chosen colors and language, and asks to unlock when the app lock is on and the app comes back to the foreground. */
abstract class Base : Activity() {
    private var look = ""
    private fun lookNow() = "${Prefs.accent}|${Prefs.background}|${Prefs.english}"
    protected open val opaque = true

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(ACCENT_STYLES.getOrElse(Prefs.accent) { ACCENT_STYLES[0] }, true)
        super.onCreate(savedInstanceState)
        if (opaque) window.setBackgroundDrawable(ColorDrawable(BG))
        look = lookNow()
    }

    override fun onResume() {
        super.onResume()
        if (look != lookNow()) return recreate() // colors or language changed in the settings

        if (!Lock.locked) return onUnlocked()
        window.decorView.alpha = 0f // nothing visible behind the prompt
        if (Lock.prompting) return
        Lock.prompting = true
        authenticate(t("Déverrouiller galleryFlows"), {
            Lock.prompting = false; Lock.locked = false
            window.decorView.alpha = 1f
            onUnlocked()
        }, { Lock.prompting = false; finish() })
    }

    open fun onUnlocked() {}
}

private val ACCENT_STYLES = listOf(R.style.Accent0, R.style.Accent1, R.style.Accent2, R.style.Accent3, R.style.Accent4, R.style.Accent5, R.style.Accent6, R.style.Accent7)

/** Pins a launcher shortcut; [cover] becomes its icon. */
fun Context.pinShortcut(id: String, label: String, cover: Bitmap?, intent: Intent) {
    val sm = getSystemService(ShortcutManager::class.java)
    if (!sm.isRequestPinShortcutSupported) return toast(t("Le lanceur ne gère pas les raccourcis"))
    sm.requestPinShortcut(ShortcutInfo.Builder(this, id).setShortLabel(label)
        .setIcon(cover?.let { Icon.createWithBitmap(it) } ?: Icon.createWithResource(this, R.drawable.ic_launcher))
        .setIntent(intent).build(), null)
}

/** ACTION_VIEW (ouvrir avec), ACTION_EDIT, ACTION_ATTACH_DATA (définir en tant que). */
fun Context.openWith(f: File, action: String, title: String) {
    val i = Intent(action).setDataAndType(Files.uri(f), mimeOf(f)).putExtra("mimeType", mimeOf(f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(i, t(title))) }.onFailure { toast("Aucune application") }
}

/**
 * "Faire défiler horizontalement": lays its one child (the list) out a quarter-turn to the left, so it scrolls sideways
 * while keeping ListView's recycling. The rows turn their cells back upright.
 */
class Turn(c: Context) : FrameLayout(c) {
    var on = false
        set(v) { field = v; requestLayout() }

    override fun onMeasure(w: Int, h: Int) {
        if (!on) return super.onMeasure(w, h)
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        if (!on) {
            child.rotation = 0f; child.translationY = 0f
            return super.onLayout(changed, l, t, r, b)
        }
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        // turned about its top-left corner, then pushed down by its length: list top = screen left
        child.pivotX = 0f; child.pivotY = 0f
        child.rotation = -90f; child.translationY = (b - t).toFloat()
    }
}
