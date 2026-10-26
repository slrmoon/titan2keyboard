package io.github.slrmoon.clack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log
import clack.core.launch.AppEntry

/** Installed apps, their icons, and SYM+key assignments. */
class AppLauncher(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("clack_launch", Context.MODE_PRIVATE)

    fun apps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        cache?.let { (at, list) -> if (now - at < CACHE_MS) return list }
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName || it.activityInfo.name.endsWith("SettingsActivity") }
            .map { AppEntry(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.id }
        cache = now to list
        return list
    }

    fun icon(app: AppEntry): Drawable? = runCatching {
        ctx.packageManager.getActivityIcon(ComponentName(app.packageName, app.activity))
    }.getOrNull()

    fun launch(app: AppEntry): Boolean = runCatching {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(i)
        true
    }.getOrElse {
        Log.w("Clack", "launch ${app.id} failed", it)
        // the activity may have been renamed by an update: fall back to the package's launch intent
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(app.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it); true } ?: false
        }.getOrDefault(false)
    }

    // --- assignments: physical key name (KEYCODE_T) → app id ---

    fun assigned(keyName: String): AppEntry? {
        val id = sp.getString(keyName, null) ?: return null
        return apps().firstOrNull { it.id == id }
            ?: apps().firstOrNull { it.packageName == id.substringBefore('/') }
    }

    fun assign(keyName: String, app: AppEntry) = sp.edit().putString(keyName, app.id).apply()
    fun unassign(keyName: String) = sp.edit().remove(keyName).apply()

    /** All assignments that still point to an installed app, sorted by key. */
    fun assignments(): List<Pair<String, AppEntry>> =
        sp.all.keys.filter { it.startsWith("KEYCODE_") }.sorted().mapNotNull { k -> assigned(k)?.let { k to it } }

    companion object {
        private const val CACHE_MS = 60_000L
        @Volatile private var cache: Pair<Long, List<AppEntry>>? = null
        fun invalidate() { cache = null }

        /** "KEYCODE_T" → "T" */
        fun keyLabel(keyName: String) = keyName.removePrefix("KEYCODE_")

    }
}
