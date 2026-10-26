package clack.core.layout

/**
 * Helpers for editing layouts on the device: a key is described as a short line of text,
 * and a layout can be written back to Pastiera JSON.
 */
object LayoutEditing {
    /** The 26 letter keys of the Titan 2 in physical rows (the bottom row is split by the space bar). */
    val TITAN2_ROWS: List<List<String>> = listOf(
        "QWERTYUIOP".map { "KEYCODE_$it" },
        "ASDFGHJKL".map { "KEYCODE_$it" },
        "ZXCV".map { "KEYCODE_$it" } + listOf(SPACE_GAP) + "BNM".map { "KEYCODE_$it" }
    )
    const val SPACE_GAP = "SPACE"

    /**
     * "з х ъ" → three multi-tap outputs; a single letter → one output. Uppercase is derived
     * unless given explicitly as "з/З х/Х". Empty text → null (key not mapped).
     */
    fun parseKey(text: String): KeyMapping? {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val outputs = parts.map { p ->
            val slash = p.indexOf('/')
            if (slash > 0 && slash < p.length - 1) KeyOutput(p.substring(0, slash), p.substring(slash + 1))
            else KeyOutput(p, upperOf(p))
        }
        return KeyMapping(outputs)
    }

    /** Inverse of [parseKey]; uppercase is spelled out only when it is not the plain uppercase. */
    fun formatKey(mapping: KeyMapping?): String = mapping?.outputs?.joinToString(" ") { o ->
        if (o.upper == upperOf(o.lower)) o.lower else "${o.lower}/${o.upper}"
    } ?: ""

    private fun upperOf(s: String) = if (s.length == 1) s.uppercase() else s.replaceFirstChar { it.uppercaseChar() }

    fun toJson(layout: KeyboardLayout): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"name\": ").append(quote(layout.name)).append(",\n")
        sb.append("  \"description\": ").append(quote(layout.description)).append(",\n")
        if (!layout.label.isNullOrBlank()) sb.append("  \"shortLabel\": ").append(quote(layout.label)).append(",\n")
        sb.append("  \"mappings\": {")
        layout.mappings.entries.forEachIndexed { i, (key, m) ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append("    ").append(quote(key)).append(": {")
            sb.append("\"lowercase\": ").append(quote(m.primary.lower))
            sb.append(", \"uppercase\": ").append(quote(m.primary.upper))
            if (m.isMultiTap) {
                sb.append(", \"multiTapEnabled\": true, \"taps\": [")
                m.outputs.forEachIndexed { j, o ->
                    if (j > 0) sb.append(", ")
                    sb.append("{\"lowercase\": ").append(quote(o.lower)).append(", \"uppercase\": ").append(quote(o.upper)).append('}')
                }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append("\n  }\n}\n")
        return sb.toString()
    }

    /** Simple "key → text" maps (Alt layer, SYM pages) in the Pastiera format: {"mappings": {...}}. */
    fun keyMapToJson(map: Map<String, String>, name: String? = null): String {
        val sb = StringBuilder("{\n")
        if (name != null) sb.append("  \"name\": ").append(quote(name)).append(",\n")
        sb.append("  \"mappings\": {")
        map.entries.forEachIndexed { i, (k, v) ->
            sb.append(if (i == 0) "\n" else ",\n").append("    ").append(quote(k)).append(": ").append(quote(v))
        }
        sb.append("\n  }\n}\n")
        return sb.toString()
    }

    /** File-name-safe id from a display name ("Моя раскладка" → "moya_raskladka"). */
    fun idFor(name: String, taken: Set<String>): String {
        val base = translit(name.lowercase()).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "layout" }.take(40)
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    private fun translit(s: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh",
            'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
            'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
            'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
        )
        return buildString { for (c in s) append(map[c] ?: c.toString()) }
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
