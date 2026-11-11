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

class SettingsSchemaDefaultsTest {
    private val schema = SettingsSchema(
        listOf(
            SettingsField.Toggle(id = "onClose", label = "On close", default = true),
            SettingsField.Path(id = "dir", label = "Folder", placeholder = "next to the database"),
            SettingsField.Number(id = "keep", label = "Keep", default = 5.0),
            SettingsField.Secret(id = "token", label = "Token"),
        ),
    )

    @Test
    fun `withDefaults fills declared defaults and lets saved values win`() {
        schema.withDefaults(mapOf("keep" to 2.0, "dir" to "")) shouldBe mapOf("onClose" to true, "keep" to 2.0)
        schema.withDefaults(mapOf("onClose" to false, "dir" to "/tmp/x")) shouldBe
            mapOf("onClose" to false, "keep" to 5.0, "dir" to "/tmp/x")
    }

    @Test
    fun `a number field rejects text that is not a number but accepts either number shape`() {
        schema.validate(mapOf("keep" to "five")) shouldBe listOf(SettingsError("keep", "'five' is not a number"))
        schema.validate(mapOf("keep" to "7")) shouldBe emptyList()
        schema.validate(mapOf("keep" to 7)) shouldBe emptyList()
        "3,5".asDoubleOrNull() shouldBe 3.5
        "true".asBoolean() shouldBe true
        null.asBoolean() shouldBe false
    }
}
