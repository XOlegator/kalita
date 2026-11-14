// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.agent.api.RECEIPT_STATUS_REJECTED
import kalita.agent.api.RECEIPT_STATUS_SAVED
import kalita.agent.api.ReceiptLine
import kalita.agent.api.ReceiptProposal
import kalita.agent.api.ReceiptProposalStore
import kalita.agent.api.ReceiptReviewModel
import kalita.storage.api.SubmitResult
import kalita.storage.local.defaultDataDirectory
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

// The receipt review window's controller half (dev_plan.md §12.19) — plain JVM, like the rest.
class AndroidAppControllerReceiptTest {
    private fun open(tempDir: Path): AndroidAppController {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        controller.addCurrency("RUB", null)
        controller.addBudgetItem(null, "Еда")
        controller.addBudgetItem(null, "Дом")
        return controller
    }

    /** A proposal file as `kalita receipt propose` leaves it on the agent's machine. */
    private fun proposalFile(tempDir: Path, controller: AndroidAppController): File {
        val ds = (controller.state.value as AndroidAppState.Ready).dataset
        val items = ds.budgetItems.filter { !it.deleted }
        val agentDir = tempDir.resolve("agent").toFile()
        val store = ReceiptProposalStore(agentDir)
        val p = store.add(
            ReceiptProposal(
                id = store.newId(), createdAt = Instant.now(), device = "agent:test", clientRequestId = "r",
                merchant = "Магазин", date = LocalDate.of(2026, 1, 5), currencyId = ds.currencies.first { !it.deleted }.id,
                accountFromId = ds.accounts.first { !it.deleted }.id, total = BigDecimal("300.00"), note = null, imagePath = null,
                lines = listOf(ReceiptLine("Хлеб", BigDecimal("100.00"), items[0].id), ReceiptLine("Сыр", BigDecimal("200.00"), items[1].id)),
                status = "pending", resolution = null,
            ),
        )
        return File(agentDir, "receipt-proposals/${p.id}.json")
    }

    @Test
    fun `an imported receipt is pending, saving writes one change set and records the decision`(@TempDir tempDir: Path) {
        val controller = open(tempDir)
        val file = proposalFile(tempDir, controller)
        val before = (controller.state.value as AndroidAppState.Ready).dataset.operations.size

        val imported = file.inputStream().use { controller.importReceipt(it) }!!
        controller.pendingReceipts() shouldHaveSize 1

        val dataset = (controller.state.value as AndroidAppState.Ready).dataset
        val model = ReceiptReviewModel(imported, dataset).also { it.mergeByItem = false }
        controller.saveReceipt(model).shouldBeInstanceOf<SubmitResult.Applied>()

        (controller.state.value as AndroidAppState.Ready).dataset.operations.size shouldBe before + 2
        controller.pendingReceipts() shouldHaveSize 0
        val dir = defaultDataDirectory(File(tempDir.resolve("private").toFile(), "Data.mdb"))
        ReceiptProposalStore(dir).find(imported.id)!!.status shouldBe RECEIPT_STATUS_SAVED
    }

    @Test
    fun `re-importing a decided receipt does not reopen it, a foreign file is refused`(@TempDir tempDir: Path) {
        val controller = open(tempDir)
        val file = proposalFile(tempDir, controller)
        val imported = file.inputStream().use { controller.importReceipt(it) }!!
        controller.rejectReceipt(imported, " не мой ")

        val again = file.inputStream().use { controller.importReceipt(it) }!!
        again.status shouldBe RECEIPT_STATUS_REJECTED
        controller.pendingReceipts() shouldHaveSize 0
        "не чек".byteInputStream().use { controller.importReceipt(it) } shouldBe null
    }

    @Test
    fun `saving is refused while the receipt does not add up to valid operations`(@TempDir tempDir: Path) {
        val controller = open(tempDir)
        val imported = proposalFile(tempDir, controller).inputStream().use { controller.importReceipt(it) }!!
        val dataset = (controller.state.value as AndroidAppState.Ready).dataset
        val model = ReceiptReviewModel(imported, dataset).also { it.rows[0].budgetItemId = null }

        controller.saveReceipt(model).shouldBeInstanceOf<SubmitResult.Failed>()
        controller.pendingReceipts() shouldHaveSize 1
    }
}
