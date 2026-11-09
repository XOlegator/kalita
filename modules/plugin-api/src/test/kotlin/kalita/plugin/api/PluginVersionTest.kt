// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PluginVersionTest {
    @Test
    fun `parses major minor patch`() {
        PluginVersion.parse("1.2.3") shouldBe PluginVersion(1, 2, 3)
    }

    @Test
    fun `rejects a malformed version string`() {
        shouldThrow<IllegalArgumentException> { PluginVersion.parse("1.2") }
    }

    @Test
    fun `compatible when same major and host is at least as new`() {
        isCompatible(required = PluginVersion(1, 0, 0), actual = PluginVersion(1, 0, 0)) shouldBe true
        isCompatible(required = PluginVersion(1, 0, 0), actual = PluginVersion(1, 2, 0)) shouldBe true
    }

    @Test
    fun `incompatible across a major version or when the host is older`() {
        isCompatible(required = PluginVersion(2, 0, 0), actual = PluginVersion(1, 9, 0)) shouldBe false
        isCompatible(required = PluginVersion(1, 5, 0), actual = PluginVersion(1, 4, 0)) shouldBe false
    }
}
