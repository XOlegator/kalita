// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.core.JournalFilter
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
}
