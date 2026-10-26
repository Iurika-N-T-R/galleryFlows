package com.galleryflows

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.reflect.KMutableProperty0

class SettingsActivity : Base() {
    private lateinit var body: LinearLayout
    private val refreshers = ArrayList<() -> Unit>() // subtitles that show live values (sizes, counts)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_back) { finish() })
            addView(TextView(this@SettingsActivity).apply { text = "Paramètres"; setTextColor(WHITE); textSize = 20f })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(ScrollView(this@SettingsActivity).apply { addView(body) }, LP(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); ins
            }
        })
        build()
    }

    override fun onUnlocked() = refreshers.forEach { it() }

    private fun build() {
        section("Général")
        action("Gérer les dossiers ajoutés", { "${Prefs.roots.size} dossier(s) sur l'écran principal" }, ::manageRoots)
        action("Gérer les dossiers exclus", { "${Prefs.excluded.size} dossier(s)" }, ::manageExcluded)
        toggle("Afficher les fichiers cachés", Prefs::showHidden, protect = { Prefs.lockHidden })

        section("Miniatures")
        toggle("Recadrer les miniatures en carré", Prefs::cropThumbs)
        toggle("Coins arrondis des dossiers", Prefs::roundFolders)

        section("Vidéos")
        toggle("Lecture automatique des vidéos", Prefs::autoplay)
        toggle("Lecture en boucle des vidéos", Prefs::loopVideos)
        toggle("Mémoriser la position de lecture", Prefs::rememberPosition)
        toggle("Ouvrir les vidéos dans une application externe", Prefs::externalPlayer)
        toggle("Gestes verticaux : luminosité à gauche, volume à droite", Prefs::videoGestures)

        section("Plein écran")
        toggle("Luminosité maximale", Prefs::maxBrightness)
        toggle("Cacher automatiquement l'interface", Prefs::autoHideUi)
        toggle("Appuyer sur les côtés pour changer de média", Prefs::tapSides)
        toggle("Fermer par un geste vers le bas", Prefs::swipeDownClose)
        toggle("Afficher les détails du média (taille, date, résolution)", Prefs::showDetails)
        action("Durée du diaporama", { "${Prefs.slideSeconds} s par média" }) {
            val values = listOf(2, 3, 5, 8, 10, 15, 30)
            AlertDialog.Builder(this).setTitle("Durée du diaporama")
                .setSingleChoiceItems(values.map { "$it secondes" }.toTypedArray(), values.indexOf(Prefs.slideSeconds)) { d, i ->
                    Prefs.slideSeconds = values[i]; d.dismiss(); refresh()
                }.show()
        }

        section("Sécurité")
        toggle("Protéger l'application (empreinte ou code)", Prefs::appLock, protect = { true })
        toggle("Protéger l'affichage des éléments cachés", Prefs::lockHidden, protect = { true })
        toggle("Protéger la suppression et le déplacement", Prefs::lockOps, protect = { true })

        section("Opérations sur les fichiers")
        toggle("Toujours ignorer la confirmation de suppression", Prefs::skipDeleteConfirm)
        toggle("Supprimer les dossiers vides après avoir supprimé leur contenu", Prefs::deleteEmptyFolders)
        toggle("Conserver la date de modification lors des opérations", Prefs::keepDates)

        section("Corbeille")
        toggle("Déplacer les éléments dans la corbeille au lieu de les supprimer", Prefs::useTrash)
        toggle("Afficher la corbeille sur l'écran principal", Prefs::showTrash)
        action("Vider la corbeille", { "${Trash.items().size} fichier(s) · ${Formatter.formatShortFileSize(this, Trash.size())} · purge auto après 30 jours" }) {
            confirm("Supprimer définitivement tout le contenu de la corbeille ?") {
                guardOps { thread { val msg = Trash.empty(this); runOnUiThread { toast(msg); Session.dirty = true; refresh() } } }
            }
        }

        section("Cache et migration")
        action("Vider le cache des miniatures", { Formatter.formatShortFileSize(this, Thumbs.size()) }) {
            thread { Thumbs.clear(); runOnUiThread { toast("Cache vidé"); refresh() } }
        }
        action("Exporter les paramètres", { "Réglages, dossiers, favoris et épingles en JSON" }) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "galleryflows-parametres.json"), EXPORT)
        }
        action("Importer les paramètres", { "Depuis un fichier exporté" }) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT)
        }

        section("À propos")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        action("galleryflows $version", { "Galerie avec navigation Dossier › Sous-dossier › … › Images" }) {}
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        runCatching {
            when (requestCode) {
                EXPORT -> contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Prefs.export().toByteArray()) }
                IMPORT -> Prefs.import(contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            }
        }.onSuccess {
            toast(if (requestCode == EXPORT) "Paramètres exportés" else "Paramètres importés")
            if (requestCode == IMPORT) { Scan.clear(); Session.dirty = true; recreate() }
        }.onFailure { toast("Échec : ${it.message}") }
    }

    private fun refresh() = refreshers.forEach { it() }

    private fun manageRoots() {
        val roots = Prefs.roots
        AlertDialog.Builder(this).setTitle("Dossiers ajoutés · toucher pour retirer")
            .setItems(roots.toTypedArray()) { _, i ->
                confirm("Retirer ${roots[i]} de l'écran principal ?") { Prefs.roots = Prefs.roots - roots[i]; Session.dirty = true; refresh() }
            }
            .setPositiveButton("Ajouter") { _, _ ->
                pickFolder("Ajouter un dossier", null) { f -> Prefs.roots = Prefs.roots + f.path; Session.dirty = true; refresh() }
            }
            .setNegativeButton("Fermer", null).show()
    }

    private fun manageExcluded() {
        val paths = Prefs.excluded.sorted()
        AlertDialog.Builder(this).setTitle("Dossiers exclus · toucher pour réafficher")
            .setItems(paths.toTypedArray()) { _, i -> Prefs.excluded = Prefs.excluded - paths[i]; Scan.clear(); Session.dirty = true; refresh() }
            .setPositiveButton("Exclure un dossier") { _, _ ->
                pickFolder("Exclure un dossier", null) { f -> Prefs.excluded = Prefs.excluded + f.path; Scan.clear(); Session.dirty = true; refresh() }
            }
            .setNegativeButton("Fermer", null).show()
    }

    // ---- rows ----

    private fun section(title: String) {
        if (body.childCount > 0) body.addView(View(this).apply { setBackgroundColor(0xff333333.toInt()) }, LP(-1, 1).apply { topMargin = dp(8) })
        body.addView(TextView(this).apply {
            text = title.uppercase(); setTextColor(ACCENT); textSize = 14f; setPadding(dp(20), dp(20), dp(20), dp(8))
        })
    }

    private fun row(title: String, sub: (() -> String)?, end: View?, onClick: () -> Unit) {
        val subView = sub?.let { TextView(this).apply { setTextColor(GREY); textSize = 13f } }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(12), dp(14))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply { text = title; setTextColor(WHITE); textSize = 16f })
                subView?.let { addView(it) }
            }, LP(0, -2, 1f))
            end?.let { addView(it) }
            setOnClickListener { onClick() }
        })
        if (subView != null && sub != null) refreshers += { subView.text = sub() }.also { it() }
    }

    private fun action(title: String, sub: () -> String, onClick: () -> Unit) = row(title, sub, null, onClick)

    /** A checkbox row bound to a Prefs flag; [protect] asks for the fingerprint before changing it. */
    private fun toggle(title: String, prop: KMutableProperty0<Boolean>, protect: () -> Boolean = { false }) {
        val box = CheckBox(this).apply { isChecked = prop.get(); isClickable = false; buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT) }
        row(title, null, box) {
            val flip = { prop.set(!prop.get()); box.isChecked = prop.get(); Scan.clear(); Session.dirty = true }
            if (protect()) authenticate(title, flip) else flip()
        }
    }

    private companion object {
        const val EXPORT = 1
        const val IMPORT = 2
    }
}
