package com.iurika.galleryflows

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
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
            addView(TextView(this@SettingsActivity).apply { text = t("Paramètres"); setTextColor(WHITE); textSize = 20f })
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
        section("Personnalisation des couleurs")
        action("Couleur d'accent", { t(ACCENTS[Prefs.accent].first) }) {
            pick("Couleur d'accent", ACCENTS.map { it.first }, Prefs.accent) { Prefs.accent = it; recreate() }
        }
        action("Couleur de fond", { t(BACKGROUNDS[Prefs.background].first) }) {
            pick("Couleur de fond", BACKGROUNDS.map { it.first }, Prefs.background) { Prefs.background = it; recreate() }
        }

        section("Général")
        toggle("Utiliser la langue anglaise", Prefs::english, then = ::recreate)
        action("Modifier le format de la date et de l'heure", { formatDate(System.currentTimeMillis()) }, ::dateDialog)
        val priorities = listOf("Rapide (miniatures légères)", "Équilibrée", "Meilleure qualité")
        action("Priorité de chargement des fichiers", { t(priorities[Prefs.loadPriority]) }) {
            pick("Priorité de chargement des fichiers", priorities, Prefs.loadPriority) { Prefs.loadPriority = it }
        }
        action("Gérer les dossiers ajoutés", { t("%d dossier(s) sur l'écran principal", Prefs.roots.size) }, ::manageRoots)
        action("Gérer les dossiers exclus", { t("%d dossier(s)", Prefs.excluded.size) }, ::manageExcluded)
        toggle("Afficher les fichiers cachés", Prefs::showHidden, protect = { Prefs.lockHidden }, rescan = true)
        toggle("Rechercher des fichiers au lieu des dossiers sur l'écran principal", Prefs::searchFiles)

        section("Miniatures")
        toggle("Recadrer les miniatures en carré", Prefs::cropThumbs)
        toggle("Animer les miniatures des GIF", Prefs::animateGifs)
        val fileStyles = listOf("Carré", "Arrondi")
        action("Style des miniatures des fichiers", { t(fileStyles[Prefs.fileStyle]) }) {
            pick("Style des miniatures des fichiers", fileStyles, Prefs.fileStyle) { Prefs.fileStyle = it }
        }
        val folderStyles = listOf("Carré", "Arrondi", "Cercle")
        action("Style des miniatures des dossiers", { t(folderStyles[Prefs.folderStyle]) }) {
            pick("Style des miniatures des dossiers", folderStyles, Prefs.folderStyle) { Prefs.folderStyle = it }
        }

        section("Défilement")
        toggle("Faire défiler les miniatures horizontalement", Prefs::horizontal)
        toggle("Activer l'actualisation par défilement depuis le haut de l'écran", Prefs::pullRefresh)

        section("Vidéos")
        toggle("Lecture automatique des vidéos", Prefs::autoplay)
        toggle("Lecture en boucle des vidéos", Prefs::loopVideos)
        toggle("Mémoriser la position de lecture", Prefs::rememberPosition)
        toggle("Ouvrir les vidéos dans une application externe", Prefs::externalPlayer)
        toggle("Gestes verticaux : luminosité à gauche, volume à droite", Prefs::videoGestures)

        section("Plein écran")
        toggle("Luminosité maximale", Prefs::maxBrightness)
        toggle("Arrière-plan et barre d'état noirs", Prefs::blackBg)
        toggle("Cacher automatiquement l'interface", Prefs::autoHideUi)
        toggle("Appuyer sur les côtés pour changer de média", Prefs::tapSides)
        toggle("Contrôler la luminosité des images par gestes verticaux", Prefs::imageBrightness)
        toggle("Fermer par un geste vers le bas", Prefs::swipeDownClose)
        toggle("Afficher une encoche si disponible", Prefs::showNotch)
        toggle("Afficher les détails du média (taille, date, résolution)", Prefs::showDetails)
        fun duration(s: Int) = if (s >= 60 && s % 60 == 0) "${s / 60} min" else "$s s"
        action("Durée du diaporama et de l'aléatoire", { t("%s par média", duration(Prefs.slideSeconds)) }) {
            val values = listOf(2, 3, 5, 8, 10, 15, 30, 60, 120, 300, 600)
            AlertDialog.Builder(this).setTitle(t("Durée par média"))
                .setSingleChoiceItems(values.map(::duration).toTypedArray(), values.indexOf(Prefs.slideSeconds)) { d, i ->
                    Prefs.slideSeconds = values[i]; d.dismiss(); refresh()
                }.show()
        }

        section("Niveau de zoom")
        toggle("Activer les options de zoom avancées", Prefs::advancedZoom, sub = "Zoom jusqu'à 4× les pixels réels au lieu de 4× l'écran")
        toggle("Pivoter les images par gestes", Prefs::gestureRotate, sub = "Deux doigts qui tournent, par quarts de tour")
        toggle("Afficher les images dans la meilleure qualité possible", Prefs::bestQuality, sub = "Recharge la zone zoomée en pleine résolution")
        toggle("Permettre un zoom avant 1:1 par double appui", Prefs::oneToOne)

        section("Barre d'actions")
        toggle("Afficher les boutons d'action", Prefs::showActions)
        action("Gérer la barre d'actions", { Prefs.actions.mapNotNull { k -> VIEWER_ACTIONS.find { it.first == k }?.second?.let { t(it) } }.joinToString(", ") }) {
            val checked = BooleanArray(VIEWER_ACTIONS.size) { VIEWER_ACTIONS[it].first in Prefs.actions }
            AlertDialog.Builder(this).setTitle(t("Gérer la barre d'actions"))
                .setMultiChoiceItems(VIEWER_ACTIONS.map { t(it.second) }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("OK") { _, _ -> Prefs.actions = VIEWER_ACTIONS.filterIndexed { i, _ -> checked[i] }.map { it.first }; refresh() }
                .setNegativeButton(t("Annuler"), null).show()
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
        action("Vider la corbeille", { t("%d fichier(s) · %s · purge auto après 30 jours", Trash.items().size, Formatter.formatShortFileSize(this, Trash.size())) }) {
            confirm(t("Supprimer définitivement tout le contenu de la corbeille ?")) {
                guardOps { thread { val msg = Trash.empty(this); runOnUiThread { toast(msg); Session.dirty = true; refresh() } } }
            }
        }

        section("Cache et migration")
        action("Vider le cache des miniatures", { Formatter.formatShortFileSize(this, Thumbs.size()) }) {
            thread { Thumbs.clear(); runOnUiThread { toast(t("Cache vidé")); refresh() } }
        }
        action("Exporter les favoris", { t("%d favori(s), un chemin par ligne", Prefs.favorites.size) }) {
            create("galleryflows-favoris.txt", "text/plain", EXPORT_FAVORITES)
        }
        action("Importer des favoris", { t("S'ajoutent aux favoris actuels") }) { open(IMPORT_FAVORITES) }
        action("Exporter les paramètres", { t("Réglages, dossiers, favoris et épingles en JSON") }) {
            create("galleryflows-parametres.json", "application/json", EXPORT)
        }
        action("Importer les paramètres", { t("Depuis un fichier exporté") }) { open(IMPORT) }

        section("À propos")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        action("galleryFlows $version", { t("Galerie avec navigation Dossier › Sous-dossier › … › Images") }) {}
    }

    private fun create(name: String, type: String, request: Int) = startActivityForResult(
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE, name), request)

    private fun open(request: Int) =
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), request)

    /** Single choice among [labels]; [onPick] gets the index. */
    private fun pick(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(t(title))
            .setSingleChoiceItems(labels.map { t(it) }.toTypedArray(), current) { d, i -> d.dismiss(); onPick(i); refresh() }.show()
    }

    private fun dateDialog() {
        val now = System.currentTimeMillis()
        val hour24 = Prefs.hour24
        val labels = DATE_FORMATS.indices.map { i -> Prefs.dateFormat.let { old -> Prefs.dateFormat = i; formatDate(now, time = false).also { Prefs.dateFormat = old } } }
        var choice = Prefs.dateFormat
        val box = CheckBox(this).apply { text = t("Format 24 heures"); isChecked = hour24; buttonTintList = ColorStateList.valueOf(ACCENT); setPadding(dp(8), 0, 0, 0) }
        AlertDialog.Builder(this).setTitle(t("Format de la date et de l'heure"))
            .setSingleChoiceItems(labels.toTypedArray(), choice) { _, i -> choice = i }
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(box) })
            .setPositiveButton("OK") { _, _ -> Prefs.dateFormat = choice; Prefs.hour24 = box.isChecked; refresh() }
            .setNegativeButton(t("Annuler"), null).show()
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        runCatching {
            fun read() = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            fun write(text: String) = contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            when (requestCode) {
                EXPORT -> write(Prefs.export())
                IMPORT -> Prefs.import(read())
                EXPORT_FAVORITES -> write(Prefs.favorites.sorted().joinToString("\n"))
                IMPORT_FAVORITES -> Prefs.favorites = Prefs.favorites + read().lines().map { it.trim() }.filter { it.startsWith("/") }
            }
        }.onSuccess {
            toast(t(when (requestCode) { EXPORT -> "Paramètres exportés"; IMPORT -> "Paramètres importés"; EXPORT_FAVORITES -> "Favoris exportés"; else -> "Favoris importés" }))
            if (requestCode != EXPORT) Session.dirty = true
            if (requestCode == IMPORT) { Scan.clear(); recreate() } else refresh()
        }.onFailure { toast(t("Échec : %s", it.message)) }
    }

    private fun refresh() = refreshers.forEach { it() }

    private fun manageRoots() {
        val roots = Prefs.roots
        AlertDialog.Builder(this).setTitle(t("Dossiers ajoutés · toucher pour retirer"))
            .setItems(roots.toTypedArray()) { _, i ->
                confirm(t("Retirer %s de l'écran principal ?", roots[i])) { Prefs.roots = Prefs.roots - roots[i]; Session.dirty = true; refresh() }
            }
            .setPositiveButton(t("Ajouter")) { _, _ ->
                pickFolder("Ajouter un dossier", null) { f -> Prefs.roots = Prefs.roots + f.path; Session.dirty = true; refresh() }
            }
            .setNegativeButton(t("Fermer"), null).show()
    }

    private fun manageExcluded() {
        val paths = Prefs.excluded.sorted()
        AlertDialog.Builder(this).setTitle(t("Dossiers exclus · toucher pour réafficher"))
            .setItems(paths.toTypedArray()) { _, i -> Prefs.excluded = Prefs.excluded - paths[i]; Scan.clear(); Session.dirty = true; refresh() }
            .setPositiveButton(t("Exclure un dossier")) { _, _ ->
                pickFolder("Exclure un dossier", null) { f -> Prefs.excluded = Prefs.excluded + f.path; Scan.clear(); Session.dirty = true; refresh() }
            }
            .setNegativeButton(t("Fermer"), null).show()
    }

    // ---- rows ----

    private fun section(title: String) {
        if (body.childCount > 0) body.addView(View(this).apply { setBackgroundColor(0xff333333.toInt()) }, LP(-1, 1).apply { topMargin = dp(8) })
        body.addView(TextView(this).apply {
            text = t(title).uppercase(); setTextColor(ACCENT); textSize = 14f; setPadding(dp(20), dp(20), dp(20), dp(8))
        })
    }

    private fun row(title: String, sub: (() -> String)?, end: View?, onClick: () -> Unit) {
        val subView = sub?.let { TextView(this).apply { setTextColor(GREY); textSize = 13f } }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(12), dp(14))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply { text = t(title); setTextColor(WHITE); textSize = 16f })
                subView?.let { addView(it) }
            }, LP(0, -2, 1f))
            end?.let { addView(it) }
            setOnClickListener { onClick() }
        })
        if (subView != null && sub != null) refreshers += { subView.text = sub() }.also { it() }
    }

    private fun action(title: String, sub: () -> String, onClick: () -> Unit) = row(title, sub, null, onClick)

    /**
     * A checkbox row bound to a Prefs flag. [protect] asks for the fingerprint before changing it,
     * [rescan] drops the folder counts (the flag changes what they count), [then] runs after the change.
     */
    private fun toggle(title: String, prop: KMutableProperty0<Boolean>, protect: () -> Boolean = { false }, rescan: Boolean = false,
                       sub: String? = null, then: () -> Unit = {}) {
        val box = CheckBox(this).apply { isChecked = prop.get(); isClickable = false; buttonTintList = ColorStateList.valueOf(ACCENT) }
        row(title, sub?.let { s -> { t(s) } }, box) {
            val flip = { prop.set(!prop.get()); box.isChecked = prop.get(); if (rescan) { Scan.clear(); Session.dirty = true }; then() }
            if (protect()) authenticate(t(title), flip) else flip()
        }
    }

    private companion object {
        const val EXPORT = 1
        const val IMPORT = 2
        const val EXPORT_FAVORITES = 3
        const val IMPORT_FAVORITES = 4
    }
}
