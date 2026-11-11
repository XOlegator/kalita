// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Per-plugin enabled flag and settings values (dev_plan.md §9.11, FR-P1), one JSON file for all
 * plugins (`plugins.json` in the app's config directory — settings belong to the installation,
 * not to any one database, §9.4). Secrets never land here: [PluginRegistry] moves
 * [kalita.plugin.api.SettingsField.Secret] values into the OS secret store before saving the rest.
 *
 * Whole-file rewrite through a temp file + atomic move, same as every other small JSON store in
 * this project — a crash mid-write leaves the previous file, never a truncated one.
 */
class PluginSettingsStore(private val file: File) {
    private var cache: JsonObject? = null

    private fun root(): JsonObject {
        cache?.let { return it }
        val loaded = if (file.isFile) {
            val text = file.readText()
            if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
        } else {
            JsonObject(emptyMap())
        }
        cache = loaded
        return loaded
    }

    private fun entry(pluginId: String): JsonObject = root()[pluginId]?.jsonObject ?: JsonObject(emptyMap())

    /** Plugins start enabled: a freshly installed one shows up working, and the user switches it off, not on. */
    fun isEnabled(pluginId: String): Boolean = entry(pluginId)["enabled"]?.jsonPrimitive?.booleanOrNull ?: true

    fun setEnabled(pluginId: String, enabled: Boolean) {
        write(pluginId, enabled = enabled, values = values(pluginId))
    }

    /** Saved values only — no schema defaults applied; see [kalita.plugin.api.SettingsSchema.withDefaults]. */
    fun values(pluginId: String): Map<String, Any?> =
        entry(pluginId)["values"]?.jsonObject?.mapValues { (_, v) -> fromJson(v) }.orEmpty()

    fun saveValues(pluginId: String, values: Map<String, Any?>) {
        write(pluginId, enabled = isEnabled(pluginId), values = values)
    }

    private fun write(pluginId: String, enabled: Boolean, values: Map<String, Any?>) {
        val updated = buildJsonObject {
            for ((id, e) in root()) if (id != pluginId) put(id, e)
            put(
                pluginId,
                buildJsonObject {
                    put("enabled", enabled)
                    put("values", JsonObject(values.filterValues { it != null }.mapValues { (_, v) -> toJson(v) }))
                },
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(updated.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cache = updated
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJson(e: JsonElement): Any? {
        val p = e as? JsonPrimitive ?: return e.toString()
        if (p is JsonNull) return null
        if (p.isString) return p.content
        return p.booleanOrNull ?: p.doubleOrNull ?: p.contentOrNull
    }
}
