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

    // FR-A4's write path (mirrors kalita.app.desktop.AppController.updateOperation/
    // softDeleteOperations/restoreOperations 1:1 — same submitChange gate already proven by the
    // addOperation tests above, so this covers the new methods' own mapping, not the gate again).

    @Test
    fun `updateOperation overwrites an existing row in place`(@TempDir tempDir: Path) {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        val before = (controller.state.value as AndroidAppState.Ready).dataset
        val id = before.operations.first { !it.deleted }.id

        val draft = OperationDraft(date = LocalDate.of(2026, 11, 7), amount = BigDecimal("999"), typeId = 2, accountFrom = 1)
        val result = controller.updateOperation(id, draft)

        result.shouldBeInstanceOf<SubmitResult.Applied>()
        val after = (controller.state.value as AndroidAppState.Ready).dataset
        after.operations.size shouldBe before.operations.size
        val updated = after.operations.first { it.id == id }
        // The MDB "Summa" column is DOUBLE — the round trip changes scale (e.g. "999.0000"),
        // not value, so compare numerically rather than with BigDecimal's scale-sensitive equals.
        (updated.amount?.compareTo(BigDecimal("999"))) shouldBe 0
        updated.date shouldBe LocalDate.of(2026, 11, 7)
    }

    @Test
    fun `softDeleteOperations marks a row deleted without removing it`(@TempDir tempDir: Path) {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        val before = (controller.state.value as AndroidAppState.Ready).dataset
        val id = before.operations.first { !it.deleted }.id
        val liveBefore = before.operations.count { !it.deleted }

        val result = controller.softDeleteOperations(listOf(id))

        result.shouldBeInstanceOf<SubmitResult.Applied>()
        val after = (controller.state.value as AndroidAppState.Ready).dataset
        after.operations.size shouldBe before.operations.size
        after.operations.first { it.id == id }.deleted shouldBe true
        after.operations.count { !it.deleted } shouldBe liveBefore - 1
    }

    @Test
    fun `restoreOperations undoes a soft delete`(@TempDir tempDir: Path) {
        // softDeleteEvery defaults to 4 — createDemoFixture already soft-deletes every 4th row.
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 4)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        val before = (controller.state.value as AndroidAppState.Ready).dataset
        val id = before.operations.first { it.deleted }.id

        val result = controller.restoreOperations(listOf(id))

        result.shouldBeInstanceOf<SubmitResult.Applied>()
        val after = (controller.state.value as AndroidAppState.Ready).dataset
        after.operations.first { it.id == id }.deleted shouldBe false
    }

    @Test
    fun `addOperation with an account also refreshes the saldo presenter`(@TempDir tempDir: Path) {
        // FR-A5's wiring (AndroidAppController.saldo), not FR-A4's — grouped here with the other
        // submitChange-triggered refresh checks rather than its own file. createDemoFixture's own
        // operations have no AccountFrom/AccountTo (see XCashFixture.kt), so SaldoService.compute
        // would see an empty dataset either way — a draft with an account is needed to prove
        // addOperation's new saldo.load(dataset) call actually feeds it something.
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        controller.saldo.state.value.saldo.lines shouldBe emptyList()

        val draft = OperationDraft(date = LocalDate.of(2026, 11, 7), amount = BigDecimal("500"), typeId = 2, accountFrom = 1)
        controller.addOperation(draft).shouldBeInstanceOf<SubmitResult.Applied>()

        val lines = controller.saldo.state.value.saldo.lines
        lines.size shouldBe 1
        lines.single().accountId shouldBe 1L
        // Same DOUBLE-column scale round trip as the updateOperation test above.
        lines.single().spent.compareTo(BigDecimal("500")) shouldBe 0
    }

    @Test
    fun `updateOperation refuses to write when the session is read-only`(@TempDir tempDir: Path) {
        val privateDir = tempDir.resolve("private").toFile().apply { mkdirs() }
        val dbFile = File(privateDir, "Data.mdb")
        createDemoFixture(dbFile, operationCount = 1)
        val blockingSession = LocalStorageProvider.open(DbLocation.Local(dbFile, defaultDataDirectory(dbFile)))
        val controller = AndroidAppController(privateDir)
        controller.openIfExists()
        val id = (controller.state.value as AndroidAppState.Ready).dataset.operations.first().id

        val result = controller.updateOperation(id, OperationDraft(date = LocalDate.now(), amount = BigDecimal.TEN))

        result.shouldBeInstanceOf<SubmitResult.Failed>()
        blockingSession.close()
    }
}
