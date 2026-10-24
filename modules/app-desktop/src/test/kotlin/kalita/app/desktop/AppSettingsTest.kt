// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AppSettingsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `default lastDatabasePath is null`() {
        AppSettings(tempDir).lastDatabasePath shouldBe null
    }

    @Test
    fun `lastDatabasePath persists across reloads`() {
        AppSettings(tempDir).lastDatabasePath = "/data/Demo.mdb"
        AppSettings(tempDir).lastDatabasePath shouldBe "/data/Demo.mdb"
    }

    @Test
    fun `clearing lastDatabasePath removes it`() {
        val s = AppSettings(tempDir)
        s.lastDatabasePath = "/data/Demo.mdb"
        s.lastDatabasePath = null
        AppSettings(tempDir).lastDatabasePath shouldBe null
    }

    @Test
    fun `recentDatabases starts empty`() {
        AppSettings(tempDir).recentDatabases.shouldBeEmpty()
    }

    @Test
    fun `addRecent moves path to front and persists`() {
        val s = AppSettings(tempDir)
        s.addRecent("/a.mdb")
        s.addRecent("/b.mdb")
        s.recentDatabases shouldContainExactly listOf("/b.mdb", "/a.mdb")
        AppSettings(tempDir).recentDatabases shouldContainExactly listOf("/b.mdb", "/a.mdb")
    }

    @Test
    fun `addRecent deduplicates`() {
        val s = AppSettings(tempDir)
        s.addRecent("/a.mdb")
        s.addRecent("/b.mdb")
        s.addRecent("/a.mdb")
        s.recentDatabases shouldContainExactly listOf("/a.mdb", "/b.mdb")
    }

    @Test
    fun `recentDatabases trimmed to MAX_RECENT`() {
        val s = AppSettings(tempDir)
        repeat(15) { s.addRecent("/db$it.mdb") }
        s.recentDatabases.size shouldBe AppSettings.MAX_RECENT
    }

    @Test
    fun `backupCount defaults to 10`() {
        AppSettings(tempDir).backupCount shouldBe 10
    }

    @Test
    fun `backupCount persists`() {
        AppSettings(tempDir).backupCount = 5
        AppSettings(tempDir).backupCount shouldBe 5
    }

    @Test
    fun `savedFilters starts empty`() {
        AppSettings(tempDir).savedFilters.shouldBeEmpty()
    }

    @Test
    fun `addOrReplaceSavedFilter persists across reloads`() {
        val s = AppSettings(tempDir)
        s.addOrReplaceSavedFilter(SavedFilter("Такси", "{}"))
        AppSettings(tempDir).savedFilters shouldContainExactly listOf(SavedFilter("Такси", "{}"))
    }

    @Test
    fun `addOrReplaceSavedFilter overwrites a filter with the same name`() {
        val s = AppSettings(tempDir)
        s.addOrReplaceSavedFilter(SavedFilter("Такси", "{}"))
        s.addOrReplaceSavedFilter(SavedFilter("Такси", "{\"v\":2}"))
        s.savedFilters shouldContainExactly listOf(SavedFilter("Такси", "{\"v\":2}"))
    }

    @Test
    fun `removeSavedFilter drops it by name`() {
        val s = AppSettings(tempDir)
        s.addOrReplaceSavedFilter(SavedFilter("Такси", "{}"))
        s.addOrReplaceSavedFilter(SavedFilter("Еда", "{}"))
        s.removeSavedFilter("Такси")
        AppSettings(tempDir).savedFilters shouldContainExactly listOf(SavedFilter("Еда", "{}"))
    }

    @Test
    fun `setSavedFilterFavorite toggles the favorite flag`() {
        val s = AppSettings(tempDir)
        s.addOrReplaceSavedFilter(SavedFilter("Такси", "{}"))
        s.setSavedFilterFavorite("Такси", true)
        AppSettings(tempDir).savedFilters shouldContainExactly listOf(SavedFilter("Такси", "{}", favorite = true))
    }
}
