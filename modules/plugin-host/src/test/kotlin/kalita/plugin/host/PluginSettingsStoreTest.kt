// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.host

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PluginSettingsStoreTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `missing file means enabled with no values`() {
        val store = PluginSettingsStore(dir.resolve("plugins.json").toFile())
        store.isEnabled("x") shouldBe true
        store.values("x") shouldBe emptyMap()
    }

    @Test
    fun `values keep their types across a reload and other plugins are untouched`() {
        val file = dir.resolve("plugins.json").toFile()
        val store = PluginSettingsStore(file)
        store.saveValues("a", mapOf("flag" to true, "n" to 2.5, "s" to "text", "gone" to null))
        store.setEnabled("b", false)
        store.saveValues("a", mapOf("flag" to false, "n" to 3, "s" to "text"))

        val reloaded = PluginSettingsStore(file)
        reloaded.values("a") shouldBe mapOf("flag" to false, "n" to 3.0, "s" to "text")
        reloaded.isEnabled("a") shouldBe true
        reloaded.isEnabled("b") shouldBe false
        dir.resolve("plugins.json.tmp").toFile().exists() shouldBe false
    }
}
