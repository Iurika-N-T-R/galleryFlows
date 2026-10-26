package com.galleryflows

import android.app.Activity
import android.app.AlertDialog
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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

const val BG = 0xff1b1b1b.toInt()
const val BAR = 0xff4f3216.toInt()
const val ACCENT = 0xfff57c00.toInt()
const val GREY = 0xff9a9a9a.toInt()
const val WHITE = 0xffffffff.toInt()

typealias LP = LinearLayout.LayoutParams

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radius: Int) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.icon(res: Int, onClick: (View) -> Unit) = ImageView(this).apply {
    setImageResource(res)
    val p = dp(12)
    setPadding(p, p, p, p)
    background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, null)
    setOnClickListener(onClick)
}

/** A square thumbnail: height follows width. */
class SquareImageView(c: Context) : ImageView(c) {
    init { scaleType = ScaleType.CENTER_CROP }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

fun Context.confirm(msg: String, onOk: () -> Unit) {
    AlertDialog.Builder(this).setMessage(msg)
        .setPositiveButton("OK") { _, _ -> onOk() }
        .setNegativeButton("Annuler", null).show()
}

fun Context.ask(title: String, initial: String, onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        setText(initial); setSingleLine()
        setSelection(0, initial.substringBeforeLast('.').length) // select the name, not the extension
    }
    AlertDialog.Builder(this).setTitle(title)
        .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
        .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
        .setNegativeButton("Annuler", null).show()
}

fun Context.choose(title: String, options: List<Pair<String, () -> Unit>>) {
    AlertDialog.Builder(this).setTitle(title)
        .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }.show()
}

/** Folder browser dialog over every storage volume (internal + SD). */
fun Context.pickFolder(title: String, start: File?, onPick: (File) -> Unit) {
    val volumes = getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
    fun open(dir: File?) {
        val entries = if (dir == null) volumes
        else dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.sortedBy { it.name.lowercase() }
        val labels = (if (dir == null) emptyList() else listOf("⬆  Dossier parent")) +
            entries.map { if (dir == null) it.path else "📁  ${it.name}" }
        val b = AlertDialog.Builder(this).setTitle(dir?.path ?: title)
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    dir == null -> open(entries[i])
                    i == 0 -> open(if (dir in volumes) null else dir.parentFile)
                    else -> open(entries[i - 1])
                }
            }
            .setNegativeButton("Annuler", null)
        if (dir != null) b.setPositiveButton("Choisir ici") { _, _ -> onPick(dir) }
            .setNeutralButton("Nouveau dossier") { _, _ ->
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
    startActivity(Intent.createChooser(i, "Partager"))
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
fun Activity.guardOps(action: () -> Unit) = if (Prefs.lockOps) authenticate("Confirmer l'opération", action) else action()

/** Every screen: asks to unlock when the app lock is on and the app comes back to the foreground. */
abstract class Base : Activity() {
    override fun onResume() {
        super.onResume()
        if (!Lock.locked) return onUnlocked()
        window.decorView.alpha = 0f // nothing visible behind the prompt
        if (Lock.prompting) return
        Lock.prompting = true
        authenticate("Déverrouiller galleryflows", {
            Lock.prompting = false; Lock.locked = false
            window.decorView.alpha = 1f
            onUnlocked()
        }, { Lock.prompting = false; finish() })
    }

    open fun onUnlocked() {}
}

/** ACTION_VIEW (ouvrir avec), ACTION_EDIT, ACTION_ATTACH_DATA (définir en tant que). */
fun Context.openWith(f: File, action: String, title: String) {
    val i = Intent(action).setDataAndType(Files.uri(f), mimeOf(f)).putExtra("mimeType", mimeOf(f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(i, title)) }.onFailure { toast("Aucune application") }
}
