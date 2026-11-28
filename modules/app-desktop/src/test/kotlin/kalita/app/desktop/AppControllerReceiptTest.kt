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
import kalita.core.DuplicateAction
import kalita.core.DuplicateControl
import kalita.core.DuplicateSession
import kalita.core.DuplicateSource
import kalita.core.EnrichField
import kalita.core.Operation
import kalita.presentation.DuplicateTexts
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
    fun `draft survives postponement and the check covers operations and total`() {
        val (ctrl, store) = open()
        val p = proposal(ctrl, store)
        val dataset = ctrl.currentDatabase()!!
        val model = ReceiptReviewModel(p, dataset)
        model.mergeByItem = false
        model.rows[0].text = "Исправлено"
        ctrl.saveReceiptReviewDraft(model)
        val restored = ReceiptReviewModel(store.find(p.id)!!, dataset)
        restored.reviewDraft() shouldBe model.reviewDraft()

        // A bank-style single line on another account that the receipt's lines add up to, and a
        // verbatim twin of the first line.
        val draft = restored.drafts().first()
        ctrl.addOperation(draft.copy(amount = BigDecimal("300"), accountFrom = 999, budgetItemId = null, comment = "с карты"))
            .shouldBeInstanceOf<SubmitResult.Applied>()
        ctrl.addOperation(draft.copy(amount = BigDecimal("100"), accountFrom = 999)).shouldBeInstanceOf<SubmitResult.Applied>()
        val live = ctrl.currentDatabase()!!
        val before = live.operations.size

        val candidates = restored.duplicateCandidates()
        val report = DuplicateControl.check(live, candidates, DuplicateSource.RECEIPT, sumTotal = true)
        report.clean shouldBe false
        report.findings.any { it.candidates == listOf(0) } shouldBe true
        report.findings.any { it.candidates == listOf(0, 1) } shouldBe true
        val text = DuplicateTexts.report(live, candidates, report)
        text.contains("счета отличаются") shouldBe true
        text.contains("#") shouldBe true
        DuplicateControl.check(Dataset.EMPTY, candidates, DuplicateSource.RECEIPT, sumTotal = true).clean shouldBe true
        ctrl.currentDatabase()!!.operations.size shouldBe before
    }

    @Test
    fun `keeping the old row fills it in and skips the new line`() {
        val (ctrl, store) = open()
        val p = proposal(ctrl, store)
        val model = ReceiptReviewModel(p, ctrl.currentDatabase()!!).also { it.mergeByItem = false }
        val draft = model.drafts().first()
        // A twin of the receipt's first line, entered by hand with no comment.
        val twinDraft = draft.copy(comment = null)
        ctrl.addOperation(twinDraft).shouldBeInstanceOf<SubmitResult.Applied>()
        val twinId = ctrl.currentDatabase()!!.operations.first { it.comment == null && it.amount?.compareTo(BigDecimal("100")) == 0 }.id
        val report = DuplicateControl.check(ctrl.currentDatabase()!!, model.duplicateCandidates(), DuplicateSource.RECEIPT, sumTotal = true)
        val session = DuplicateSession(model.duplicateCandidates(), report, DuplicateSource.RECEIPT)
        val finding = session.next()!!
        finding.rows.single().operation.id shouldBe twinId

        // «Оставить и дополнить»: the blank comment is filled from the receipt and the matched
        // line is not written; the untouched second line still is.
        session.decide(finding, DuplicateAction.KEEP_OLD)
        val plan = session.plan()
        plan.inserts shouldBe setOf(1)
        plan.updates[twinId]!![EnrichField.COMMENT].let { it as String }.contains("Хлеб") shouldBe true
        val before = ctrl.currentDatabase()!!.operations.size
        ctrl.saveReceipt(model, plan).shouldBeInstanceOf<SubmitResult.Applied>()
        val after = ctrl.currentDatabase()!!
        after.operations.size shouldBe before + 1
        (after.operations.first { it.id == twinId }.comment ?: "").contains("Хлеб") shouldBe true
        after.operations.first { it.id == twinId }.deleted shouldBe false
        ctrl.pendingReceipts() shouldHaveSize 0
    }

    @Test
    fun `replace deletes the rows it names and writes the receipt's lines`() {
        val (ctrl, store) = open()
        val p = proposal(ctrl, store)
        val model = ReceiptReviewModel(p, ctrl.currentDatabase()!!).also { it.mergeByItem = false }
        val draft = model.drafts().first()
        ctrl.addOperation(draft.copy(comment = null)).shouldBeInstanceOf<SubmitResult.Applied>()
        val twinId = ctrl.currentDatabase()!!.operations.first { it.comment == null && it.amount?.compareTo(BigDecimal("100")) == 0 }.id

        val report = DuplicateControl.check(ctrl.currentDatabase()!!, model.duplicateCandidates(), DuplicateSource.RECEIPT, sumTotal = true)
        val session = DuplicateSession(model.duplicateCandidates(), report, DuplicateSource.RECEIPT)
        while (true) {
            val finding = session.next() ?: break
            session.decide(finding, DuplicateAction.REPLACE)
        }
        val plan = session.plan()
        plan.inserts shouldBe setOf(0, 1)
        plan.softDeletes shouldBe setOf(twinId)

        val size = ctrl.currentDatabase()!!.operations.size
        ctrl.saveReceipt(model, plan).shouldBeInstanceOf<SubmitResult.Applied>()
        val after = ctrl.currentDatabase()!!
        after.operations.size shouldBe size + 2
        after.operations.first { it.id == twinId }.deleted shouldBe true
        ctrl.pendingReceipts() shouldHaveSize 0
    }
}
