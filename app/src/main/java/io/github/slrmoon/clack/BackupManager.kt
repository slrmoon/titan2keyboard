package io.github.slrmoon.clack

import android.content.Context
import android.net.Uri
import clack.core.backup.BackupArchive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export and import of everything the user set up: settings, quick-launch assignments,
 * own and edited layouts, Alt layer, SYM pages and learned words. One zip file.
 */
class BackupManager(context: Context) {
    private val ctx = context.applicationContext

    data class Summary(val prefs: Int, val layouts: Int, val wordFiles: Int, val otherFiles: Int)

    fun suggestedName(): String = "clack-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".zip"

    /** Blocking. */
    fun export(uri: Uri): Summary {
        Languages.get(ctx).saveNow()
        val prefs = PREF_FILES.associateWith { name ->
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.filterValues { it != null }
        }
        val files = LinkedHashMap<String, ByteArray>()
        for (f in dataFiles()) files[f.relativeTo(ctx.filesDir).invariantSeparatorsPath] = f.readBytes()
        val content = BackupArchive.Content(
            appVersion = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?",
            created = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date()),
            prefs = prefs,
            files = files
        )
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { BackupArchive.write(it, content) }
        return summarize(content)
    }

    /** Reads a backup without applying it (to show what is inside). Blocking. */
    fun read(uri: Uri): BackupArchive.Content =
        ctx.contentResolver.openInputStream(uri)!!.use { BackupArchive.read(it) }

    /** Replaces the current settings and data with the backup's. Blocking. */
    fun restore(content: BackupArchive.Content): Summary {
        // files first: when the preferences change, the keyboard reloads and must see the new files
        for (f in dataFiles()) f.delete()
        for ((path, bytes) in content.files) {
            if (!BackupArchive.isSafePath(path) || !isDataPath(path)) continue
            val target = File(ctx.filesDir, path)
            if (!target.canonicalPath.startsWith(ctx.filesDir.canonicalPath + File.separator)) continue
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        LayoutStore.clearCache()
        Languages.get(ctx).reloadLearned()
        for ((name, values) in content.prefs) {
            if (name !in PREF_FILES) continue
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((k, v) in values) {
                @Suppress("UNCHECKED_CAST")
                when (v) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is Set<*> -> e.putStringSet(k, v as Set<String>)
                }
            }
            e.commit()
        }
        // make sure the running keyboard reloads even if the settings were identical
        Prefs(ctx).sp.edit().putLong("restored_at", System.currentTimeMillis()).commit()
        return summarize(content)
    }

    fun summarize(c: BackupArchive.Content) = Summary(
        prefs = c.prefs.values.sumOf { it.size },
        layouts = c.files.keys.count { it.startsWith("layouts/") },
        wordFiles = c.files.keys.count { it.startsWith("user_") },
        otherFiles = c.files.keys.count { !it.startsWith("layouts/") && !it.startsWith("user_") }
    )

    /** The user's own data in files/: layouts, Alt layer, SYM pages, learned words. */
    private fun dataFiles(): List<File> =
        ctx.filesDir.walkTopDown().filter { it.isFile && isDataPath(it.relativeTo(ctx.filesDir).invariantSeparatorsPath) }.toList()

    private fun isDataPath(path: String) =
        (path.startsWith("layouts/") && path.endsWith(".json") && path.count { it == '/' } == 1) ||
            path == "alt_layer.json" ||
            Regex("sym_page\\d+\\.json").matches(path) ||
            Regex("user_[a-z]{2,3}\\.tsv").matches(path)

    companion object {
        /** Settings and quick-launch assignments. Update-check timestamps are not worth carrying over. */
        private val PREF_FILES = listOf("clack", "clack_launch")
    }
}
