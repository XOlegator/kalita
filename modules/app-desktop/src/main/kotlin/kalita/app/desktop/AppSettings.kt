// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** A filter saved under a name from the «Фильтр» panel (dev_plan.md §6.3, FR-28) — settings only, never in `Data.mdb`. */
data class SavedFilter(val name: String, val filterJson: String, val favorite: Boolean = false)

/**
 * Persistent application settings (dev_plan.md §9.9, FR-2, FR-3). Stored as a properties file in
 * [configDir] (`~/.config/kalita` on Linux, `%APPDATA%/kalita` on Windows). Qt-specific state
 * (window geometry, dock layout) is handled separately by [QSettings][io.qt.core.QSettings].
 */
class AppSettings(private val configDir: Path) {
    private val file: Path = configDir.resolve("settings.properties")
    private val props = Properties()

    init {
        if (Files.exists(file)) {
            Files.newBufferedReader(file).use { props.load(it) }
        }
    }

    var lastDatabasePath: String?
        get() = props.getProperty("lastDatabasePath")
        set(value) {
            if (value != null) props.setProperty("lastDatabasePath", value)
            else props.remove("lastDatabasePath")
            save()
        }

    var recentDatabases: List<String>
        get() {
            val count = props.getProperty("recent.count")?.toIntOrNull() ?: 0
            return (0 until count).mapNotNull { props.getProperty("recent.$it") }
        }
        set(value) {
            val old = recentDatabases
            old.indices.forEach { props.remove("recent.$it") }
            val trimmed = value.take(MAX_RECENT)
            props.setProperty("recent.count", trimmed.size.toString())
            trimmed.forEachIndexed { i, path -> props.setProperty("recent.$i", path) }
            save()
        }

    var backupCount: Int
        get() = props.getProperty("backupCount")?.toIntOrNull() ?: DEFAULT_BACKUP_COUNT
        set(value) {
            props.setProperty("backupCount", value.toString())
            save()
        }

    fun addRecent(path: String) {
        val current = recentDatabases.toMutableList()
        current.remove(path)
        current.add(0, path)
        recentDatabases = current
    }

    var savedFilters: List<SavedFilter>
        get() {
            val count = props.getProperty("filter.count")?.toIntOrNull() ?: 0
            return (0 until count).mapNotNull { i ->
                val name = props.getProperty("filter.$i.name") ?: return@mapNotNull null
                val json = props.getProperty("filter.$i.json") ?: return@mapNotNull null
                val favorite = props.getProperty("filter.$i.favorite")?.toBoolean() ?: false
                SavedFilter(name, json, favorite)
            }
        }
        set(value) {
            val old = savedFilters
            old.indices.forEach { i ->
                props.remove("filter.$i.name")
                props.remove("filter.$i.json")
                props.remove("filter.$i.favorite")
            }
            props.setProperty("filter.count", value.size.toString())
            value.forEachIndexed { i, f ->
                props.setProperty("filter.$i.name", f.name)
                props.setProperty("filter.$i.json", f.filterJson)
                props.setProperty("filter.$i.favorite", f.favorite.toString())
            }
            save()
        }

    fun addOrReplaceSavedFilter(filter: SavedFilter) {
        savedFilters = savedFilters.filter { it.name != filter.name } + filter
    }

    fun removeSavedFilter(name: String) {
        savedFilters = savedFilters.filter { it.name != name }
    }

    fun setSavedFilterFavorite(name: String, favorite: Boolean) {
        savedFilters = savedFilters.map { if (it.name == name) it.copy(favorite = favorite) else it }
    }

    private fun save() {
        Files.createDirectories(configDir)
        Files.newBufferedWriter(file).use { props.store(it, "Kalita settings") }
    }

    companion object {
        const val MAX_RECENT = 10
        const val DEFAULT_BACKUP_COUNT = 10

        fun default(): AppSettings {
            val os = System.getProperty("os.name", "")
            val dir = when {
                os.startsWith("Windows", ignoreCase = true) ->
                    Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"), "kalita")
                else ->
                    Path.of(System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "kalita")
            }
            return AppSettings(dir)
        }
    }
}
