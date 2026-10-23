// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.mdb.createXCashSchemaFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AppControllerTest {
    @TempDir
    lateinit var tempDir: Path

    private fun fixture(): File = createXCashSchemaFixture(tempDir.resolve("Data.mdb").toFile())

    private fun controller(): AppController {
        val settings = AppSettings(tempDir.resolve("config"))
        return AppController(settings)
    }

    @Test
    fun `initial state is NoDatabase`() {
        controller().state.value.shouldBeInstanceOf<AppState.NoDatabase>()
    }

    @Test
    fun `openDatabase transitions to Ready`() {
        val ctrl = controller()
        val result = ctrl.openDatabase(fixture())
        result.shouldBeInstanceOf<OpenResult.Success>()
        ctrl.state.value.shouldBeInstanceOf<AppState.Ready>()
    }

    @Test
    fun `openDatabase stores path in settings`() {
        val settings = AppSettings(tempDir.resolve("config"))
        val ctrl = AppController(settings)
        val f = fixture()
        ctrl.openDatabase(f)
        settings.lastDatabasePath shouldBe f.absolutePath
        settings.recentDatabases[0] shouldBe f.absolutePath
    }

    @Test
    fun `closeDatabase returns to NoDatabase`() {
        val ctrl = controller()
        ctrl.openDatabase(fixture())
        ctrl.closeDatabase()
        ctrl.state.value.shouldBeInstanceOf<AppState.NoDatabase>()
    }

    @Test
    fun `opening a nonexistent file returns Failure`() {
        val ctrl = controller()
        val result = ctrl.openDatabase(File(tempDir.toFile(), "missing.mdb"))
        result.shouldBeInstanceOf<OpenResult.Failure>()
        ctrl.state.value.shouldBeInstanceOf<AppState.Error>()
    }

    @Test
    fun `presenters are fed on open`() {
        val ctrl = controller()
        ctrl.openDatabase(fixture())
        ctrl.dictionary.state.value.accounts shouldBe emptyList()
    }

    @Test
    fun `refresh detects no change when file is untouched`() {
        val ctrl = controller()
        ctrl.openDatabase(fixture())
        ctrl.refresh() shouldBe false
    }
}
