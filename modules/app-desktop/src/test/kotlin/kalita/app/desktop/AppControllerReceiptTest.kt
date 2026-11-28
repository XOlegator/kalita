// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.agent.api.RECEIPT_STATUS_REJECTED
import kalita.agent.api.RECEIPT_STATUS_SAVED
import kalita.agent.api.ReceiptLine
import kalita.agent.api.ReceiptLearningStore
import kalita.agent.api.ReceiptProposal
import kalita.agent.api.ReceiptProposalStore
import kalita.agent.api.ReceiptReviewModel
import kalita.core.Dataset
import kalita.core.Operation
import kalita.storage.api.SubmitResult
import kalita.storage.local.defaultDataDirectory
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

class AppControllerReceiptTest {
    @TempDir
    lateinit var tempDir: Path

    private fun open(): Pair<AppController, ReceiptProposalStore> {
        val dbFile = createDemoFixture(tempDir.resolve("Data.mdb").toFile())
        val ctrl = AppController(AppSettings(tempDir.resolve("config")))
        ctrl.openDatabase(dbFile)
        ctrl.addCurrency("RUB", null)
        ctrl.addBudgetItem(null, "Еда")
        ctrl.addBudgetItem(null, "Дом")
        return ctrl to ReceiptProposalStore(defaultDataDirectory(dbFile))
    }

    private fun proposal(ctrl: AppController, store: ReceiptProposalStore): ReceiptProposal {
        val ds = ctrl.currentDatabase()!!
        val items = ds.budgetItems.filter { !it.deleted }
        return store.add(
            ReceiptProposal(
                id = store.newId(), createdAt = Instant.now(), device = "agent:test", clientRequestId = "r",
                merchant = "Магазин", date = LocalDate.of(2026, 1, 5), currencyId = ds.currencies.first { !it.deleted }.id,
                accountFromId = ds.accounts.first { !it.deleted }.id, total = BigDecimal("300.00"), note = null, imagePath = null,
                lines = listOf(ReceiptLine("Хлеб", BigDecimal("100.00"), items[0].id), ReceiptLine("Сыр", BigDecimal("200.00"), items[1].id)),
                status = "pending", resolution = null,
            ),
        )
    }

    @Test
    fun `saveReceipt writes the lines as operations in one change set and records the decision`() {
        val (ctrl, store) = open()
        val before = ctrl.currentDatabase()!!.operations.size
        val p = proposal(ctrl, store)
        ctrl.pendingReceipts() shouldHaveSize 1

        val model = ReceiptReviewModel(p, ctrl.currentDatabase()!!)
        model.mergeByItem = false
        model.rows[1].budgetItemId = model.rows[0].budgetItemId // the person moved «Сыр» to the same item as «Хлеб»

        ctrl.saveReceipt(model).shouldBeInstanceOf<SubmitResult.Applied>()

        ctrl.currentDatabase()!!.operations.size shouldBe before + 2
        ctrl.pendingReceipts() shouldHaveSize 0
        val saved = store.find(p.id)!!
        saved.status shouldBe RECEIPT_STATUS_SAVED
        saved.resolution!!.savedOperations shouldBe 2
        ReceiptLearningStore(defaultDataDirectory(tempDir.resolve("Data.mdb").toFile()))
            .hints(listOf("Сыр"), "Магазин", ctrl.currentDatabase()!!).single().budgetItemId shouldBe model.rows[0].budgetItemId
    }

    @Test
    fun `an incomplete receipt is not written, a rejected one writes nothing`() {
        val (ctrl, store) = open()
        val before = ctrl.currentDatabase()!!.operations.size
        val p = proposal(ctrl, store)
        val model = ReceiptReviewModel(p, ctrl.currentDatabase()!!)
        model.accountFromId = null

        ctrl.saveReceipt(model).shouldBeInstanceOf<SubmitResult.Failed>()
        ctrl.currentDatabase()!!.operations.size shouldBe before

        ctrl.rejectReceipt(p, "не мой чек")
        ctrl.currentDatabase()!!.operations.size shouldBe before
        store.find(p.id)!!.status shouldBe RECEIPT_STATUS_REJECTED
        store.find(p.id)!!.resolution!!.reason shouldBe "не мой чек"
    }

    @Test
    fun `draft survives postponement and duplicates cover operations and total`() {
        val (ctrl, store) = open()
        val p = proposal(ctrl, store)
        val dataset = ctrl.currentDatabase()!!
        val model = ReceiptReviewModel(p, dataset)
        model.mergeByItem = false
        model.rows[0].text = "Исправлено"
        ctrl.saveReceiptReviewDraft(model)
        val restored = ReceiptReviewModel(store.find(p.id)!!, dataset)
        restored.reviewDraft() shouldBe model.reviewDraft()

        val draft = restored.drafts().first()
        val operations = listOf(
            Operation(101, draft.date, BigDecimal("100"), draft.currencyId, draft.budgetItemId, draft.typeId, accountFrom = draft.accountFrom),
            Operation(102, draft.date, BigDecimal("300"), draft.currencyId, draft.budgetItemId, draft.typeId, accountFrom = 999),
        )
        val warning = receiptDuplicateWarning(restored, Dataset(operations = operations))
        warning.contains("Операция 1") shouldBe true
        warning.contains("#101") shouldBe true
        warning.contains("Итого по чеку") shouldBe true
        warning.contains("#102") shouldBe true
        warning.contains("счета отличаются") shouldBe true
        receiptDuplicateWarning(restored, Dataset.EMPTY) shouldBe ""
        val group = listOf(
            operations[0].copy(id = 201, amount = BigDecimal("40")),
            operations[0].copy(id = 202, amount = BigDecimal("60")),
        )
        val groupWarning = receiptDuplicateWarning(restored, Dataset(operations = group))
        groupWarning.contains("#201") shouldBe true
        groupWarning.contains("#202") shouldBe true
        val many = (1L..21L).map { operations[0].copy(id = it, amount = BigDecimal.ONE) }
        receiptDuplicateWarning(restored, Dataset(operations = many)).contains("Поиск групп неполный") shouldBe true
        ctrl.currentDatabase()!!.operations.size shouldBe dataset.operations.size
    }
}
