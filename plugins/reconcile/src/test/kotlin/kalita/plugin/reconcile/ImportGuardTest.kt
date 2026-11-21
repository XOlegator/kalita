// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ImportGuardTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `unfinished import blocks another instance until explicitly resolved`() {
        val first = ImportGuard(dir.toFile())
        val second = ImportGuard(dir.toFile())
        first.begin() shouldBe true
        second.begin() shouldBe false
        first.finish()
        second.begin() shouldBe true
        second.finish()
    }
}