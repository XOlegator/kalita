// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SettingsSchemaTest {
    private val schema = SettingsSchema(
        listOf(
            SettingsField.Text(id = "login", label = "Login", required = true),
            SettingsField.Secret(id = "token", label = "API token", required = true),
            SettingsField.Choice(id = "mode", label = "Mode", options = listOf("read-only", "draft", "write"), default = "draft"),
        ),
    )

    @Test
    fun `rejects duplicate field ids`() {
        shouldThrow<IllegalArgumentException> {
            SettingsSchema(listOf(SettingsField.Text(id = "x", label = "a"), SettingsField.Text(id = "x", label = "b")))
        }
    }

    @Test
    fun `reports missing required fields`() {
        schema.validate(mapOf("mode" to "write")) shouldBe
            listOf(SettingsError("login", "Required"), SettingsError("token", "Required"))
    }

    @Test
    fun `reports a choice value outside its options`() {
        val values = mapOf("login" to "a", "token" to "b", "mode" to "sudo")
        schema.validate(values) shouldBe listOf(SettingsError("mode", "'sudo' is not one of [read-only, draft, write]"))
    }

    @Test
    fun `valid values produce no errors`() {
        schema.validate(mapOf("login" to "a", "token" to "b", "mode" to "write")) shouldBe emptyList()
    }

    @Test
    fun `the empty schema accepts anything`() {
        SettingsSchema.EMPTY.validate(mapOf("anything" to "goes")) shouldBe emptyList()
    }
}
