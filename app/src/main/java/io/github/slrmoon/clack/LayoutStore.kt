package io.github.slrmoon.clack

import android.content.Context
import android.net.Uri
import clack.core.layout.AltLayer
import clack.core.layout.KeyboardLayout
import clack.core.layout.LayoutEditing
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Layouts come from two places: bundled assets (assets/layouts/<id>.json) and the user's files
 * (files/layouts/<id>.json: imported, created or edited on the device). A user file with the
 * same id as a bundled one overrides it; deleting the file brings the original back.
 * All files use the Pastiera JSON format.
 */
class LayoutStore(private val context: Context) {
    enum class Origin { BUILT_IN, MODIFIED, USER }
    data class Entry(val id: String, val name: String, val origin: Origin)

    private val userDir = File(context.filesDir, "layouts").apply { mkdirs() }
    private val altFile = File(context.filesDir, "alt_layer.json")

    private fun bundledIds(): List<String> =
        context.assets.list("layouts").orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }

    private fun userIds(): List<String> =
        userDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name.removeSuffix(".json") }.sorted()

    fun available(): List<Entry> {
        val bundled = bundledIds()
        val user = userIds()
        val out = ArrayList<Entry>()
        for (id in bundled) load(id)?.let { out.add(Entry(id, it.name, if (id in user) Origin.MODIFIED else Origin.BUILT_IN)) }
        for (id in user) if (id !in bundled) load(id)?.let { out.add(Entry(id, it.name, Origin.USER)) }
        return out
    }

    fun allIds(): Set<String> = (bundledIds() + userIds()).toSet()

    fun load(id: String): KeyboardLayout? = cache[id] ?: runCatching { KeyboardLayout.parse(id, rawJson(id)!!) }
        .getOrNull()?.also { cache[id] = it }

    /** The JSON text behind a layout (the user's copy if there is one). */
    fun rawJson(id: String): String? {
        val f = File(userDir, "$id.json")
        if (f.exists()) return f.readText()
        return runCatching { context.assets.open("layouts/$id.json").bufferedReader().use { it.readText() } }.getOrNull()
    }

    /** Validates and saves JSON for [id]. Throws with a readable message if it is not a layout. */
    fun saveJson(id: String, json: String): KeyboardLayout {
        val layout = KeyboardLayout.parse(id, json)
        File(userDir, "$id.json").writeText(json)
        cache.remove(id)
        return layout
    }

    fun save(layout: KeyboardLayout) { saveJson(layout.id, LayoutEditing.toJson(layout)) }

    /** Validates and stores a layout picked by the user. Returns its id. */
    fun import(uri: Uri): String {
        val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.removeSuffix(".json") ?: "layout"
        val id = LayoutEditing.idFor(name, allIds())
        saveJson(id, json)
        return id
    }

    /** Removes the user's file: deletes a user layout, or restores a modified built-in one. */
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

    companion object {
        /** Shared by the keyboard and the settings screen, so an edit is seen by both. */
        private val cache = ConcurrentHashMap<String, KeyboardLayout>()
    }
}
