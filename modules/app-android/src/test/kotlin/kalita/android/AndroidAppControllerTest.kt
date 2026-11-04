// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.core.JournalFilter
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path

// Plain JVM test (not androidTest): AndroidAppController itself has no android.* dependency, and
// storage-mdb's own unit tests already prove unpatched Jackcess works fine on a desktop JVM — the
// System.Logger patch (PatchJackcessForAndroidTask) only matters once this actually runs under
// ART, which is what the instrumented XCashScenarioInstrumentedTest exists to prove instead.
class AndroidAppControllerTest {
    @Test
    fun `importAndOpen opens a picked database and feeds the journal presenter`(@TempDir tempDir: Path) {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 12)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })

        fixture.inputStream().use { controller.importAndOpen(it) }

        val state = controller.state.value
        state.shouldBeInstanceOf<AndroidAppState.Ready>()
        state.readOnly shouldBe false
        state.dataset.operations.count { !it.deleted } shouldBe 9

        controller.journal.applyFilter(JournalFilter.NONE)
        controller.journal.state.value.rows.size shouldBe 9
    }

    @Test
    fun `a broken database surfaces as an error state, not a crash`(@TempDir tempDir: Path) {
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })

        "not an mdb file".byteInputStream().use { controller.importAndOpen(it) }

        controller.state.value.shouldBeInstanceOf<AndroidAppState.Error>()
    }

    @Test
    fun `closeDatabase returns to NoDatabase`(@TempDir tempDir: Path) {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }

        controller.closeDatabase()

        controller.state.value.shouldBeInstanceOf<AndroidAppState.NoDatabase>()
    }

    @Test
    fun `openIfExists does nothing on a fresh install`(@TempDir tempDir: Path) {
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })

        controller.openIfExists()

        controller.state.value.shouldBeInstanceOf<AndroidAppState.NoDatabase>()
    }

    @Test
    fun `a database left over from a previous launch reopens by itself`(@TempDir tempDir: Path) {
        val privateDir = tempDir.resolve("private").toFile().apply { mkdirs() }
        // Simulates FR-A1's "the last database opens at launch": no picker involved here, the
        // file is simply already sitting in app storage from a previous process, same as it
        // would be after a real importAndOpen on an earlier run.
        createDemoFixture(File(privateDir, "Data.mdb"), operationCount = 3, softDeleteEvery = 4)
        val controller = AndroidAppController(privateDir)

        controller.openIfExists()

        val state = controller.state.value
        state.shouldBeInstanceOf<AndroidAppState.Ready>()
        controller.journal.applyFilter(JournalFilter.NONE)
        controller.journal.state.value.rows.size shouldBe 3
    }

    @Test
    fun `exportTo copies the current database out byte for byte`(@TempDir tempDir: Path) {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 2)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }

        val exported = ByteArrayOutputStream()
        controller.exportTo(exported)

        exported.toByteArray() shouldBe fixture.readBytes()
    }
}
