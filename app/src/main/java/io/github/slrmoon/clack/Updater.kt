package io.github.slrmoon.clack

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import clack.core.update.Release
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub Releases: the CI publishes every build of main as the "latest" release.
 * Only the settings screen talks to the network; the keyboard itself never does.
 */
class Updater(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("clack_update", Context.MODE_PRIVATE)

    val installedVersionCode: Long
        get() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)

    val installedVersionName: String
        get() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Automatic checks happen at most every few hours. */
    fun shouldAutoCheck(): Boolean = System.currentTimeMillis() - sp.getLong(K_LAST_CHECK, 0) > AUTO_CHECK_EVERY_MS

    /** Blocking. Returns the latest release, or throws with a message for the user. */
    fun check(): Release {
        sp.edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply()
        // version.json is a plain release download: no API rate limit (60 requests/hour per IP,
        // easily used up on mobile networks where many people share one address).
        open(VERSION_FILE).use { r ->
            when (r.code) {
                200 -> return Release.parseVersionFile(r.text()) ?: throw IOException("Не удалось прочитать version.json.")
                404 -> {} // older release without version.json: ask the API below
                else -> throw IOException("GitHub ответил кодом ${r.code}.")
            }
        }
        open(API_LATEST, api = true).use { r ->
            when (r.code) {
                200 -> return Release.parse(r.text()) ?: throw IOException("В релизе нет APK.")
                404 -> throw IOException("Релиз не найден. Репозиторий должен быть публичным, и в нём должен быть релиз latest.")
                403, 429 -> throw IOException("GitHub временно ограничил запросы с вашего IP, попробуйте позже.")
                else -> throw IOException("GitHub ответил кодом ${r.code}.")
            }
        }
    }

    /** Blocking download into the cache; [progress] gets 0..100. */
    fun download(release: Release, progress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update.apk")
        open(release.apkUrl).use { r ->
            if (r.code != 200) throw IOException("Загрузка не удалась (код ${r.code}).")
            val total = r.conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            r.conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
        }
        return out
    }

    private class Response(val conn: HttpURLConnection, val code: Int) : AutoCloseable {
        fun text(): String = conn.inputStream.bufferedReader().use { it.readText() }
        override fun close() = conn.disconnect()
    }

    /** GET following redirects across hosts (release files redirect to GitHub's file storage). */
    private fun open(start: String, api: Boolean = false): Response {
        var url = URL(start)
        repeat(6) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Clack-keyboard")
                if (api) setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: throw IOException("Пустое перенаправление")
                conn.disconnect()
                url = URL(url, next)
            } else return Response(conn, code)
        }
        throw IOException("Слишком много перенаправлений.")
    }

    /** Android must allow this app to install packages ("Install unknown apps"). */
    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Hands the APK to the system installer; Android shows its own confirmation. */
    fun install(apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("clack.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }

    companion object {
        const val REPO = "slrmoon/titan2keyboard"
        private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/tags/latest"
        private const val VERSION_FILE = "https://github.com/$REPO/releases/download/latest/version.json"
        private const val K_LAST_CHECK = "last_check"
        private const val AUTO_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}

/** Receives the installer's answer: show its confirmation screen, or report the result. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка"
                Toast.makeText(context, "Обновление не установлено: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
