package clack.core.layout

import clack.core.Json

/** One output of a key: what it types without and with Shift. */
data class KeyOutput(val lower: String, val upper: String) {
    fun forShift(shift: Boolean) = if (shift) upper else lower
}

/**
 * Mapping for one physical key. [outputs] has at least one element; more than one
 * means the key supports multi-tap (tap again quickly to cycle to the next output).
 */
data class KeyMapping(val outputs: List<KeyOutput>) {
    init { require(outputs.isNotEmpty()) }
    val primary: KeyOutput get() = outputs[0]
    val isMultiTap: Boolean get() = outputs.size > 1
}

/**
 * A letter layout in the Pastiera JSON format:
 *
 * ```
 * { "name": "...", "description": "...",
 *   "mappings": { "KEYCODE_Q": { "lowercase": "й", "uppercase": "Й",
 *                                "multiTapEnabled": true,
 *                                "taps": [ {"lowercase": "й", "uppercase": "Й"}, ... ] } } }
 * ```
 *
 * Keys are Android key code names, so the same file works for any device; what differs
 * between devices is only where those keys physically sit (see KeyGeometry).
 */
data class KeyboardLayout(
    val id: String,
    val name: String,
    val description: String,
    val mappings: Map<String, KeyMapping>,
    /** Optional short label for the bar and notifications ("RU", "ЙЦ", "EN"). Extra field, ignored by Pastiera. */
    val label: String? = null
) {
    /** Language guessed from the script of the letters: "ru" for Cyrillic, "en" otherwise. */
    val language: String by lazy {
        val letters = mappings.values.flatMap { m -> m.outputs.map { it.lower } }.joinToString("")
        val cyr = letters.count { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }
        val lat = letters.count { it in 'a'..'z' }
        if (cyr > lat) "ru" else "en"
    }

    /** Short label for the status bar: the layout's own label, or its language ("RU" / "EN"). */
    val shortLabel: String get() = label?.takeIf { it.isNotBlank() } ?: language.uppercase()

    companion object {
        fun parse(id: String, json: String): KeyboardLayout {
            val root = Json.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Layout root must be an object")
            val mappingsRaw = root["mappings"] as? Map<*, *> ?: throw IllegalArgumentException("Layout has no \"mappings\" object")
            val mappings = LinkedHashMap<String, KeyMapping>()
            for ((k, v) in mappingsRaw) {
                val key = k as String
                val obj = v as? Map<*, *> ?: continue
                val lower = obj["lowercase"] as? String ?: continue
                val upper = obj["uppercase"] as? String ?: lower.uppercase()
                val outputs = ArrayList<KeyOutput>()
                val multi = obj["multiTapEnabled"] == true
                val taps = obj["taps"] as? List<*>
                if (multi && taps != null) {
                    for (t in taps) {
                        val tm = t as? Map<*, *> ?: continue
                        val tl = tm["lowercase"] as? String ?: continue
                        val tu = tm["uppercase"] as? String ?: tl.uppercase()
                        outputs.add(KeyOutput(tl, tu))
                    }
                }
                if (outputs.isEmpty()) outputs.add(KeyOutput(lower, upper))
                mappings[key] = KeyMapping(outputs)
            }
            if (mappings.isEmpty()) throw IllegalArgumentException("Layout defines no keys")
            return KeyboardLayout(
                id = id,
                name = root["name"] as? String ?: id,
                description = root["description"] as? String ?: "",
                mappings = mappings,
                label = (root["shortLabel"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * Second layer of the letter keys (digits and symbols), reached with Alt.
 * Pastiera format: { "mappings": { "KEYCODE_Q": "0", ... } }.
 */
data class AltLayer(val mappings: Map<String, String>) {
    operator fun get(keyName: String): String? = mappings[keyName]

    companion object {
        fun parse(json: String): AltLayer {
            val root = Json.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Alt layer root must be an object")
            val m = root["mappings"] as? Map<*, *> ?: throw IllegalArgumentException("Alt layer has no \"mappings\"")
            val out = LinkedHashMap<String, String>()
            for ((k, v) in m) if (k is String && v is String) out[k] = v
            return AltLayer(out)
        }
    }
}
