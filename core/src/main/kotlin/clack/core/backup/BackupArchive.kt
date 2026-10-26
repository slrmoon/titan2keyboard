package clack.core.backup

import clack.core.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup is a zip:
 *   clack-backup.json        — format marker, app version, date
 *   prefs/<name>.json        — one SharedPreferences file: {"key": {"t": "s|b|i|l|f|set", "v": value}}
 *   files/<path>             — app files (layouts, Alt layer, SYM pages, learned words)
 */
object BackupArchive {
    const val MARKER = "clack-backup.json"
    const val FORMAT = 1

    data class Content(
        val appVersion: String,
        val created: String,
        val prefs: Map<String, Map<String, Any?>>,
        val files: Map<String, ByteArray>
    )

    fun write(out: OutputStream, content: Content) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write(("{\"format\": $FORMAT, \"app\": ${q(content.appVersion)}, \"created\": ${q(content.created)}}\n").toByteArray())
            zip.closeEntry()
            for ((name, values) in content.prefs) {
                zip.putNextEntry(ZipEntry("prefs/$name.json"))
                zip.write(prefsToJson(values).toByteArray())
                zip.closeEntry()
            }
            for ((path, bytes) in content.files) {
                require(isSafePath(path)) { "bad path $path" }
                zip.putNextEntry(ZipEntry("files/$path"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** Reads a backup; throws IllegalArgumentException with a readable message if it is not one. */
    fun read(input: InputStream, maxBytes: Long = 200L * 1024 * 1024): Content {
        var marker: Map<*, *>? = null
        val prefs = LinkedHashMap<String, Map<String, Any?>>()
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zip.readBytes()
                total += bytes.size
                if (total > maxBytes) throw IllegalArgumentException("Файл слишком большой для резервной копии Clack")
                val name = e.name
                when {
                    name == MARKER -> marker = Json.parse(String(bytes)) as? Map<*, *>
                    name.startsWith("prefs/") && name.endsWith(".json") -> {
                        val prefName = name.removePrefix("prefs/").removeSuffix(".json")
                        if (isSafePath(prefName) && '/' !in prefName) prefs[prefName] = prefsFromJson(String(bytes))
                    }
                    name.startsWith("files/") -> {
                        val path = name.removePrefix("files/")
                        if (isSafePath(path)) files[path] = bytes
                    }
                }
            }
        }
        val m = marker ?: throw IllegalArgumentException("Это не резервная копия Clack")
        val format = (m["format"] as? Double)?.toInt() ?: 0
        if (format > FORMAT) throw IllegalArgumentException("Копия сделана более новой версией Clack — обновите приложение")
        return Content(m["app"] as? String ?: "?", m["created"] as? String ?: "", prefs, files)
    }

    /** No absolute paths, no "..", no backslashes: a backup can only write inside the app's own folder. */
    fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && '\\' !in path && ':' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun prefsToJson(values: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in values) {
            val (t, enc) = when (v) {
                is String -> "s" to q(v)
                is Boolean -> "b" to v.toString()
                is Int -> "i" to v.toString()
                is Long -> "l" to v.toString()
                is Float -> "f" to v.toString()
                is Set<*> -> "set" to v.filterIsInstance<String>().joinToString(",", "[", "]") { q(it) }
                else -> continue
            }
            if (!first) sb.append(",")
            first = false
            sb.append("\n ").append(q(k)).append(": {\"t\": \"").append(t).append("\", \"v\": ").append(enc).append("}")
        }
        return sb.append("\n}\n").toString()
    }

    fun prefsFromJson(json: String): Map<String, Any?> {
        val root = Json.parse(json) as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((k, entry) in root) {
            val e = entry as? Map<*, *> ?: continue
            val v = e["v"]
            out[k as String] = when (e["t"]) {
                "s" -> v as? String
                "b" -> v as? Boolean
                "i" -> (v as? Double)?.toInt()
                "l" -> (v as? Double)?.toLong()
                "f" -> (v as? Double)?.toFloat()
                "set" -> (v as? List<*>)?.filterIsInstance<String>()?.toSet()
                else -> null
            } ?: continue
        }
        return out
    }

    private fun q(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
