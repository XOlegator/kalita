// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.core.JournalFilter
import kalita.core.OperationDraft
import kalita.storage.api.DbLocation
import kalita.storage.api.SubmitResult
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

// FR-A3's write path (plain JVM, same reasoning as AndroidAppControllerTest: no android.* here).
class AndroidAppControllerOperationsTest {
    @Test
    fun `addOperation inserts a row and refreshes the dataset and journal`(@TempDir tempDir: Path) {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        val before = (controller.state.value as AndroidAppState.Ready).dataset.operations.count { !it.deleted }

        val draft = OperationDraft(date = LocalDate.of(2026, 11, 7), amount = BigDecimal("150"), typeId = 2, accountFrom = 1)
        val result = controller.addOperation(draft)

        result.shouldBeInstanceOf<SubmitResult.Applied>()
        val state = controller.state.value
        state.shouldBeInstanceOf<AndroidAppState.Ready>()
        state.dataset.operations.count { !it.deleted } shouldBe before + 1

        controller.journal.applyFilter(JournalFilter.NONE)
        controller.journal.state.value.rows.size shouldBe before + 1
    }

    @Test
    fun `addOperation refuses to write when the session is read-only`(@TempDir tempDir: Path) {
        val privateDir = tempDir.resolve("private").toFile().apply { mkdirs() }
        val dbFile = File(privateDir, "Data.mdb")
        createDemoFixture(dbFile, operationCount = 1)
        // Holds the write lock before the controller ever opens the file — same situation as the
        // desktop app or another Kalita instance already having it open (FR-4).
        val blockingSession = LocalStorageProvider.open(DbLocation.Local(dbFile, defaultDataDirectory(dbFile)))
        val controller = AndroidAppController(privateDir)
        controller.openIfExists()
        val state = controller.state.value
        state.shouldBeInstanceOf<AndroidAppState.Ready>()
        state.readOnly shouldBe true

        val result = controller.addOperation(OperationDraft(date = LocalDate.now(), amount = BigDecimal.TEN))

        result.shouldBeInstanceOf<SubmitResult.Failed>()
        blockingSession.close()
    }

    @Test
    fun `addOperation on a fresh install with no database open fails cleanly`(@TempDir tempDir: Path) {
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })

        val result = controller.addOperation(OperationDraft(date = LocalDate.now(), amount = BigDecimal.ONE))

        result.shouldBeInstanceOf<SubmitResult.Failed>()
    }
}
