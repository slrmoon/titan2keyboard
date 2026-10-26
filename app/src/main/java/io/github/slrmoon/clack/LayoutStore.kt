package io.github.slrmoon.clack

import android.content.Context
import android.net.Uri
import clack.core.layout.AltLayer
import clack.core.layout.KeyboardLayout
import java.io.File

/**
 * Layouts come from two places: bundled assets (assets/layouts/<id>.json) and files the user
 * imported (files/layouts/<id>.json). Both use the Pastiera JSON format.
 */
class LayoutStore(private val context: Context) {
    data class Entry(val id: String, val name: String, val imported: Boolean)

    private val userDir = File(context.filesDir, "layouts").apply { mkdirs() }
    private val altFile = File(context.filesDir, "alt_layer.json")
    private val cache = HashMap<String, KeyboardLayout>()

    fun available(): List<Entry> {
        val out = ArrayList<Entry>()
        val bundled = context.assets.list("layouts").orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
        val imported = userDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name.removeSuffix(".json") }
        for (id in bundled) if (id !in imported) load(id)?.let { out.add(Entry(id, it.name, false)) }
        for (id in imported) load(id)?.let { out.add(Entry(id, it.name, true)) }
        return out
    }

    fun load(id: String): KeyboardLayout? = cache[id] ?: runCatching {
        val f = File(userDir, "$id.json")
        val json = if (f.exists()) f.readText() else context.assets.open("layouts/$id.json").bufferedReader().use { it.readText() }
        KeyboardLayout.parse(id, json)
    }.getOrNull()?.also { cache[id] = it }

    /** Validates and stores a layout picked by the user. Returns its id. */
    fun import(uri: Uri): String {
        val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.removeSuffix(".json") ?: "layout"
        val id = name.lowercase().replace(Regex("[^a-z0-9_а-яё-]+"), "_").trim('_').ifEmpty { "layout" }
        KeyboardLayout.parse(id, json) // throws with a readable message if the file is not a layout
        File(userDir, "$id.json").writeText(json)
        cache.remove(id)
        return id
    }

    fun delete(id: String) {
        File(userDir, "$id.json").delete()
        cache.remove(id)
    }

    fun altLayer(): AltLayer {
        if (altFile.exists()) runCatching { return AltLayer.parse(altFile.readText()) }
        return AltLayer.parse(context.assets.open("alt/titan2.json").bufferedReader().use { it.readText() })
    }

    fun importAltLayer(uri: Uri) {
        val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        AltLayer.parse(json)
        altFile.writeText(json)
    }

    fun resetAltLayer() { altFile.delete() }
    fun hasCustomAltLayer() = altFile.exists()
}
