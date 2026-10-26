package io.github.slrmoon.clack

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last error on the device so it can be copied from the settings screen and sent
 * to the developer (there is no logcat on a phone without a computer).
 */
object CrashLog {
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(error, fatal = true, where = thread.name)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Errors in background work are logged but must not take the keyboard down. */
    fun record(error: Throwable, fatal: Boolean = false, where: String = Thread.currentThread().name) {
        Log.e("Clack", "error in $where", error)
        val ctx = appContext ?: return
        runCatching {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            val text = buildString {
                append("Clack ").append(version).append(if (fatal) " — сбой" else " — ошибка (клавиатура продолжила работу)").append('\n')
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())).append('\n')
                append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('\n')
                append("thread: ").append(where).append("\n\n")
                append(sw.toString().take(12_000))
            }
            file(ctx).writeText(text)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { file(context).delete() }

    private fun file(context: Context) = File(context.filesDir, "last_error.txt")
}
