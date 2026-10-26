package clack.core.update

import clack.core.Json

/** The newest published build, as described by the GitHub releases API. */
data class Release(
    val versionName: String,
    /** Build number; the app's versionCode is the CI run number, which is the last part of the version. */
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long
) {
    fun isNewerThan(installedVersionCode: Long) = versionCode > installedVersionCode

    companion object {
        private val VERSION = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /**
         * Parses version.json, published next to the APK by CI:
         * {"versionName": "0.1.19", "versionCode": 19, "notes": "...", "apk": "https://…/clack.apk", "size": 1234}
         * Fetched as a plain release download, which is not subject to the GitHub API rate limit.
         */
        fun parseVersionFile(json: String): Release? {
            val root = Json.parse(json) as? Map<*, *> ?: return null
            return Release(
                versionName = root["versionName"] as? String ?: return null,
                versionCode = (root["versionCode"] as? Double)?.toInt() ?: return null,
                notes = (root["notes"] as? String)?.trim().orEmpty(),
                apkUrl = root["apk"] as? String ?: return null,
                apkSize = (root["size"] as? Double)?.toLong() ?: 0L
            )
        }

        /** Parses GET /repos/{owner}/{repo}/releases/tags/latest. Null if there is no usable APK. */
        fun parse(json: String): Release? {
            val root = Json.parse(json) as? Map<*, *> ?: return null
            val name = root["name"] as? String ?: return null
            val m = VERSION.find(name) ?: return null
            val assets = root["assets"] as? List<*> ?: return null
            val apk = assets.mapNotNull { it as? Map<*, *> }.firstOrNull { (it["name"] as? String)?.endsWith(".apk") == true } ?: return null
            return Release(
                versionName = m.value,
                versionCode = m.groupValues[3].toInt(),
                notes = (root["body"] as? String)?.trim().orEmpty(),
                apkUrl = apk["browser_download_url"] as? String ?: return null,
                apkSize = (apk["size"] as? Double)?.toLong() ?: 0L
            )
        }
    }
}
