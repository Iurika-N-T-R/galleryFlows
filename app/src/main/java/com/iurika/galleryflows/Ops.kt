package com.iurika.galleryflows

import android.content.Context
import android.media.MediaScannerConnection
import android.os.storage.StorageManager
import java.io.File

object Ops {
    /** [name] in [dir], or "name (1).ext", "name (2).ext"… if taken. */
    fun uniqueTarget(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || base == name) "" else ".$it" }
        var f = File(dir, name)
        var i = 1
        while (f.exists()) f = File(dir, "$base (${i++})$ext")
        return f
    }

    /** Rename when possible (same volume, instant), else copy then delete (internal <-> SD). */
    fun moveFile(f: File, target: File): Boolean = f.renameTo(target) || runCatching {
        f.copyTo(target)
        target.setLastModified(f.lastModified())
        f.delete()
    }.getOrElse { target.delete(); false }

    fun transfer(c: Context, files: Collection<File>, dest: File, move: Boolean): String {
        var ok = 0
        val changed = ArrayList<String>()
        for (f in files) {
            if (move && f.parentFile == dest) { ok++; continue }
            val target = uniqueTarget(dest, f.name)
            val done = if (move) moveFile(f, target) else runCatching {
                f.copyTo(target)
                if (Prefs.keepDates) target.setLastModified(f.lastModified())
                true
            }.getOrElse { target.delete(); false }
            if (done) {
                ok++
                changed += target.path
                if (move) { changed += f.path; Prefs.renamed(f, target) }
            }
        }
        if (move) cleanEmpty(files)
        rescan(c, changed)
        return "${if (move) "Déplacé" else "Copié"} : $ok/${files.size}"
    }

    /** To the trash when it's on (and the file isn't already there), otherwise gone for good. */
    fun delete(c: Context, files: Collection<File>, permanent: Boolean = false): String {
        val toTrash = Prefs.useTrash && !permanent
        val gone = files.filter { f ->
            if (toTrash && !Trash.contains(f)) Trash.put(c, f)
            else f.delete().also { if (it) Trash.forget(f) }
        }
        Prefs.favorites = Prefs.favorites - gone.map { it.path }.toSet()
        cleanEmpty(gone)
        rescan(c, gone.map { it.path })
        return "${if (toTrash) "Mis à la corbeille" else "Supprimé"} : ${gone.size}/${files.size}"
    }

    fun rename(c: Context, f: File, name: String): File? {
        if (name.isBlank() || '/' in name) return null
        val target = File(f.parentFile, name)
        if (target.exists() || !f.renameTo(target)) return null
        Prefs.renamed(f, target)
        rescan(c, listOf(f.path, target.path))
        return target
    }

    /** Hide / show a folder the standard way, so other galleries hide it too. */
    fun setHidden(dir: File, hidden: Boolean) = File(dir, ".nomedia").let { if (hidden) it.createNewFile() else it.delete() }

    // "Supprimer les dossiers vides": the folders we just emptied, never a root or a trash folder
    private fun cleanEmpty(files: Collection<File>) {
        if (!Prefs.deleteEmptyFolders) return
        for (d in files.mapNotNull { it.parentFile }.distinct()) {
            if (d.path in Prefs.roots || d.name == Trash.DIR) continue
            val left = d.listFiles() ?: continue
            if (left.all { it.isFile && it.name == ".nomedia" }) { left.forEach { it.delete() }; d.delete() }
        }
    }

    // Keeps the system MediaStore (other apps, the old gallery) in sync with what we changed
    fun rescan(c: Context, paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(c.applicationContext, paths.toTypedArray(), null, null)
    }
}

/** One hidden trash folder per storage volume, so trashing is a rename (instant, even for big videos). */
object Trash {
    const val DIR = ".galleryflows-trash"
    private const val KEEP_DAYS = 30L

    fun contains(f: File) = f.path in Prefs.trash

    fun items(): List<File> = Prefs.trash.entries.sortedByDescending { it.value.time }.map { File(it.key) }.filter { it.exists() }

    fun originalOf(f: File) = Prefs.trash[f.path]?.original

    fun size() = items().sumOf { it.length() }

    @Synchronized fun put(c: Context, f: File): Boolean {
        val volume = c.getSystemService(StorageManager::class.java).getStorageVolume(f)?.directory ?: return false
        val dir = File(volume, DIR).apply { mkdirs() }
        File(dir, ".nomedia").createNewFile()
        val target = Ops.uniqueTarget(dir, f.name)
        if (!f.renameTo(target)) return false
        Prefs.trash = Prefs.trash + (target.path to TrashEntry(f.path, System.currentTimeMillis()))
        return true
    }

    @Synchronized fun forget(f: File) { if (contains(f)) Prefs.trash = Prefs.trash - f.path }

    fun restore(c: Context, files: Collection<File>): String {
        val changed = ArrayList<String>()
        val ok = files.count { f ->
            val original = File(originalOf(f) ?: return@count false)
            original.parentFile?.mkdirs()
            val target = Ops.uniqueTarget(original.parentFile!!, original.name)
            Ops.moveFile(f, target).also { if (it) { forget(f); changed += target.path } }
        }
        Ops.rescan(c, changed)
        return "Restauré : $ok/${files.size}"
    }

    fun empty(c: Context) = Ops.delete(c, items(), permanent = true)

    /** Drops trash entries older than 30 days, and entries whose file vanished. */
    fun purgeOld(c: Context) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        Prefs.trash.filter { !File(it.key).exists() }.keys.forEach { forget(File(it)) }
        val old = Prefs.trash.filter { it.value.time < limit }.keys.map(::File)
        if (old.isNotEmpty()) Ops.delete(c, old, permanent = true)
    }
}
