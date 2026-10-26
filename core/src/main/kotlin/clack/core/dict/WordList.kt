package clack.core.dict

import java.io.BufferedReader

/**
 * Bundled dictionary format: one entry per line, "word<TAB>zipf×100",
 * where zipf is log10 of frequency per billion words (wordfreq's scale, ~0..8).
 * Lines starting with '#' are comments.
 */
object WordList {
    fun read(reader: BufferedReader): Map<String, Float> {
        val out = LinkedHashMap<String, Float>(1 shl 17)
        reader.forEachLine { line ->
            if (line.isEmpty() || line[0] == '#') return@forEachLine
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val f = line.substring(tab + 1).toIntOrNull() ?: return@forEachLine
            out[line.substring(0, tab)] = f / 100f
        }
        return out
    }
}
