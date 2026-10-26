package clack.core.launch

import clack.core.layout.KeyboardLayout

/** A launchable app as the quick launcher sees it. */
data class AppEntry(val label: String, val packageName: String, val activity: String) {
    /** Stable id used to store shortcut assignments. */
    val id: String get() = "$packageName/$activity"
}

/**
 * Matching apps for "SYM + letter" quick launch.
 *
 * A physical key stands for different letters in different layouts (the T key is "t" in QWERTY
 * and "е" in ЙЦУКЕН), so an app matches if its name starts with any of them: SYM+T finds both
 * "Telegram" and "Еда".
 */
object AppFilter {
    /** All letters a key produces across [layouts], plus its Latin name (KEYCODE_T → "t"). */
    fun lettersForKey(keyName: String, layouts: List<KeyboardLayout>): Set<String> {
        val out = LinkedHashSet<String>()
        val latin = keyName.removePrefix("KEYCODE_")
        if (latin.length == 1 && latin[0].isLetter()) out.add(latin.lowercase())
        for (l in layouts) {
            val m = l.mappings[keyName] ?: continue
            for (o in m.outputs) if (o.lower.isNotEmpty()) out.add(o.lower.lowercase())
        }
        return out
    }

    /** Apps whose name starts with one of [letters], sorted by name. */
    fun startingWith(apps: List<AppEntry>, letters: Set<String>): List<AppEntry> =
        apps.filter { app -> val n = normalize(app.label); letters.any { n.startsWith(it) } }
            .sortedBy { normalize(it.label) }

    /**
     * Narrowing while the user keeps typing in the picker: every word of [query] must start
     * a word of the app name ("go ma" → "Google Maps"), or the name contains the query.
     */
    fun search(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = normalize(query).trim()
        if (q.isEmpty()) return apps.sortedBy { normalize(it.label) }
        val parts = q.split(' ').filter { it.isNotEmpty() }
        fun rank(app: AppEntry): Int? {
            val name = normalize(app.label)
            val words = name.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
            return when {
                name.startsWith(q) -> 0
                parts.all { p -> words.any { it.startsWith(p) } } -> 1
                q.length >= 3 && name.contains(q) -> 2
                else -> null
            }
        }
        return apps.mapNotNull { a -> rank(a)?.let { a to it } }
            .sortedWith(compareBy<Pair<AppEntry, Int>> { it.second }.thenBy { normalize(it.first.label) })
            .map { it.first }
    }

    private fun normalize(s: String) = s.lowercase().replace('ё', 'е')
}
