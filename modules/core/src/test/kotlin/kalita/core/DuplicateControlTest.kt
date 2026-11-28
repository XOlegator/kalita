// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

private val DAY = LocalDate.of(2026, 1, 5)

private fun dataset(vararg operations: Operation) = Dataset(
    operations = operations.toList(),
    operationTypes = listOf(
        OperationType(2, "Расход", null, -1),
        OperationType(3, "Доход", null, 1),
    ),
    accounts = listOf(Account(1, "Наличные"), Account(2, "Банк")),
    counterparties = listOf(Counterparty(5, "Пятёрочка")),
    budgetItems = listOf(BudgetItem(10, null, "Еда", null), BudgetItem(11, null, "Дом", null)),
)

private fun op(id: Long, amount: String, day: LocalDate = DAY, accountFrom: Long? = 1, budgetItemId: Long? = 10, comment: String? = null) = Operation(
    id, day, BigDecimal(amount), 1, budgetItemId, 2, comment = comment, accountFrom = accountFrom, accountTo = null,
)

private fun datasetOf(operations: List<Operation>) = Dataset(
    operations = operations,
    operationTypes = listOf(
        OperationType(2, "Расход", null, -1),
        OperationType(3, "Доход", null, 1),
    ),
    accounts = listOf(Account(1, "Наличные"), Account(2, "Банк")),
    counterparties = listOf(Counterparty(5, "Пятёрочка")),
    budgetItems = listOf(BudgetItem(10, null, "Еда", null), BudgetItem(11, null, "Дом", null)),
)

private fun candidate(label: String, draft: OperationDraft) = DuplicateCandidate(label, draft)

private val EXPENSE_DRAFT = OperationDraft(DAY, BigDecimal("100"), 1, budgetItemId = 10, typeId = 2, accountFrom = 1)

class DuplicateControlTest {
    // --- check ---

    @Test
    fun `a draft matching one row asks about it`() {
        val ds = dataset(op(1, "100"), op(2, "50", DAY.minusDays(2)))
        val report = DuplicateControl.check(ds, listOf(candidate("Операция 1", EXPENSE_DRAFT)), DuplicateSource.MANUAL)
        report.clean.shouldBeFalse()
        report.findings shouldHaveSize 1
        val finding = report.findings.single()
        finding.candidates shouldBe listOf(0)
        finding.rows.single().operation.id shouldBe 1L
        finding.difference shouldBe BigDecimal.ZERO
    }

    @Test
    fun `receipt lines are asked one by one and by their total against one bank row`() {
        val ds = dataset(op(1, "100", comment = "Пятёрочка"))
        val lines = listOf(
            candidate("Операция 1", EXPENSE_DRAFT.copy(amount = BigDecimal("40"), comment = "хлеб")),
            candidate("Операция 2", EXPENSE_DRAFT.copy(amount = BigDecimal("60"), comment = "сыр")),
        )
        val report = DuplicateControl.check(ds, lines, DuplicateSource.RECEIPT, sumTotal = true)
        // Neither line alone matches, but their total does.
        report.findings shouldHaveSize 1
        val finding = report.findings.single()
        finding.candidates shouldBe listOf(0, 1)
        finding.rows.single().operation.id shouldBe 1L
        // Without the total the same receipt raises nothing.
        DuplicateControl.check(ds, lines, DuplicateSource.RECEIPT, sumTotal = false).clean.shouldBeTrue()
    }

    @Test
    fun `tolerance and days window come from the options`() {
        val ds = dataset(op(1, "99.50"), op(2, "100", DAY.plusDays(5)))
        val strict = DuplicateControl.check(
            ds, listOf(candidate("Операция 1", EXPENSE_DRAFT)), DuplicateSource.MANUAL,
            options = DuplicateCheckOptions(amountTolerance = BigDecimal.ZERO, daysWindow = 0),
        )
        strict.clean.shouldBeTrue()
        val loose = DuplicateControl.check(
            ds, listOf(candidate("Операция 1", EXPENSE_DRAFT)), DuplicateSource.MANUAL,
            options = DuplicateCheckOptions(amountTolerance = BigDecimal("1"), daysWindow = 5),
        )
        // One question per draft: the best row, the near one listed as an alternative.
        loose.findings shouldHaveSize 1
        loose.findings.single().rows.single().operation.id shouldBe 2L
        loose.findings.single().alternatives.single().operations.single().operation.id shouldBe 1L
    }

    @Test
    fun `an edited row does not match itself`() {
        val ds = dataset(op(1, "100"))
        val report = DuplicateControl.check(ds, listOf(candidate("Операция", EXPENSE_DRAFT)), DuplicateSource.EDIT, editingId = 1)
        report.clean.shouldBeTrue()
    }

    @Test
    fun `incomplete drafts are not checked`() {
        val ds = dataset(op(1, "100"))
        val report = DuplicateControl.check(ds, listOf(candidate("Операция", EXPENSE_DRAFT.copy(currencyId = null))), DuplicateSource.MANUAL)
        report.clean.shouldBeTrue()
    }

    // --- enrichment ---

    @Test
    fun `an account change is suggested from a bank line and only offered from a hand-typed draft`() {
        val old = op(1, "100", comment = null)
        val finding = DuplicateFinding(
            listOf(0),
            listOf(DuplicateOperation(old, accountFromMismatch = true, accountToMismatch = false)),
            BigDecimal.ZERO,
        )
        val candidates = listOf(candidate("Строка выписки", EXPENSE_DRAFT.copy(accountFrom = 2, comment = "карта")))
        val changes = DuplicateEnrichment.changes(finding, candidates, DuplicateSource.IMPORT)
        changes.filter { it.field == EnrichField.ACCOUNT_FROM }.single().suggested.shouldBeTrue()
        changes.filter { it.field == EnrichField.COMMENT }.single().suggested.shouldBeTrue()
        DuplicateEnrichment.changes(finding, candidates, DuplicateSource.MANUAL)
            .filter { it.field == EnrichField.ACCOUNT_FROM }.single().suggested.shouldBeFalse()
    }

    @Test
    fun `blank fields are always worth filling`() {
        val old = op(1, "100", accountFrom = null, comment = null)
        val finding = DuplicateFinding(
            listOf(0),
            listOf(DuplicateOperation(old, accountFromMismatch = true, accountToMismatch = false)),
            BigDecimal.ZERO,
        )
        val candidates = listOf(candidate("Операция", EXPENSE_DRAFT.copy(accountFrom = 1, comment = "хлеб")))
        val changes = DuplicateEnrichment.changes(finding, candidates, DuplicateSource.MANUAL).filter { it.suggested }
        changes.map { it.rowId to it.field } shouldBe listOf(1L to EnrichField.ACCOUNT_FROM, 1L to EnrichField.COMMENT)
    }

    @Test
    fun `several drafts cannot enrich one row`() {
        val old = op(1, "100", comment = null)
        val finding = DuplicateFinding(listOf(0, 1), listOf(DuplicateOperation(old, false, false)), BigDecimal.ZERO)
        val candidates = listOf(
            candidate("Операция 1", EXPENSE_DRAFT.copy(amount = BigDecimal("40"))),
            candidate("Операция 2", EXPENSE_DRAFT.copy(amount = BigDecimal("60"))),
        )
        DuplicateEnrichment.changes(finding, candidates, DuplicateSource.IMPORT).shouldBeEmpty()
    }

    // --- session ---

    @Test
    fun `session turns the answers into one plan`() {
        val ds = dataset(op(1, "100"), op(2, "100", comment = "старая"))
        val candidates = listOf(candidate("Операция 1", EXPENSE_DRAFT.copy(comment = "новая")))
        val session = DuplicateSession(candidates, DuplicateControl.check(ds, candidates, DuplicateSource.MANUAL), DuplicateSource.MANUAL)
        // One question per draft: the best row first, the other equal row as an alternative.
        session.total shouldBe 1
        val first = session.next()!!
        first.rows.single().operation.id shouldBe 1L
        first.alternatives shouldHaveSize 1
        val actions = session.actions(first)
        actions.contains(DuplicateAction.REPLACE).shouldBeTrue()
        actions.contains(DuplicateAction.LATER).shouldBeFalse() // a manual draft has no «позже»

        session.decide(first, DuplicateAction.REPLACE)
        session.next() shouldBe null // the alternative row was already listed; nothing else to ask
        session.done.shouldBeTrue()
        val plan = session.plan()
        plan.inserts shouldBe setOf(0)
        plan.softDeletes shouldBe setOf(1L)
        plan.updates shouldBe emptyMap()
    }

    @Test
    fun `keeping the old row fills it in and drops the new draft`() {
        val ds = dataset(op(1, "100", comment = null))
        val candidates = listOf(candidate("Строка выписки", EXPENSE_DRAFT.copy(comment = "из банка")))
        val session = DuplicateSession(candidates, DuplicateControl.check(ds, candidates, DuplicateSource.IMPORT), DuplicateSource.IMPORT)
        val finding = session.next()!!
        session.decide(finding, DuplicateAction.KEEP_OLD)
        val plan = session.plan()
        plan.inserts.shouldBeEmpty()
        plan.softDeletes.shouldBeEmpty()
        plan.updates[1L]!![EnrichField.COMMENT] shouldBe "из банка"
        session.outcome(0).shouldBeInstanceOf<DuplicateSession.Outcome.Merge>()
    }

    @Test
    fun `undecided drafts are written as usual`() {
        val ds = dataset(op(1, "100"))
        val candidates = listOf(
            candidate("Операция 1", EXPENSE_DRAFT),
            candidate("Операция 2", EXPENSE_DRAFT.copy(amount = BigDecimal("55"))),
        )
        val session = DuplicateSession(candidates, DuplicateControl.check(ds, candidates, DuplicateSource.MANUAL), DuplicateSource.MANUAL)
        session.decide(session.next()!!, DuplicateAction.KEEP_OLD)
        val plan = session.plan()
        plan.inserts shouldBe setOf(1)
        plan.updates shouldBe emptyMap()
    }

    @Test
    fun `later ends the pass without decisions`() {
        val ds = dataset(op(1, "100"))
        val candidates = listOf(candidate("Операция 1", EXPENSE_DRAFT))
        val session = DuplicateSession(candidates, DuplicateControl.check(ds, candidates, DuplicateSource.RECEIPT), DuplicateSource.RECEIPT)
        val finding = session.next()!!
        session.actions(finding).contains(DuplicateAction.LATER).shouldBeTrue()
        session.decide(finding, DuplicateAction.LATER)
        session.done.shouldBeTrue()
        session.plan().inserts shouldBe setOf(0) // nothing was decided, so the receipt would be written as usual
        session.next() shouldBe null
    }

    // --- scan ---

    @Test
    fun `scan finds two equal rows and names the less detailed one`() {
        val ds = dataset(
            op(1, "100", comment = "подробный"),
            op(2, "100"),
            op(3, "70"),
        )
        val scan = DuplicateControl.scan(ds, DAY.minusDays(1), DAY.plusDays(1))
        scan.groups shouldHaveSize 1
        val group = scan.groups.single()
        group.rows.map { it.id }.toSet() shouldBe setOf(1L, 2L)
        group.suggested shouldBe setOf(2L) // the row without a comment is the less detailed one
    }

    @Test
    fun `scan finds a single line against a day's rows that add up to it`() {
        val ds = dataset(
            op(1, "100", comment = "с карты"),
            op(2, "40"),
            op(3, "60"),
        )
        val scan = DuplicateControl.scan(ds, DAY, DAY)
        scan.groups shouldHaveSize 1
        scan.groups.single().suggested shouldBe setOf(1L) // the itemized lines are the detail worth keeping
    }

    @Test
    fun `scan respects the period`() {
        val ds = dataset(
            op(1, "100", DAY),
            op(2, "100", DAY.minusDays(1)),
            op(3, "55", DAY.plusDays(1)),
            op(4, "55", DAY.plusDays(2)),
        )
        // Only rows dated inside the period are scanned; a partner just outside it still shows up.
        val scan = DuplicateControl.scan(ds, DAY, DAY)
        scan.groups shouldHaveSize 1
        scan.groups.single().rows.map { it.id }.toSet() shouldBe setOf(1L, 2L)
    }

    @Test
    fun `scan reports truncation from tight limits`() {
        val ds = datasetOf((1L..20L).map { op(it, "60") } + (21L..40L).map { op(it, "40") })
        val scan = DuplicateControl.scan(ds, DAY, DAY, DuplicateCheckOptions(daysWindow = 0))
        scan.truncated.shouldBeTrue()
        scan.groups.isNotEmpty().shouldBeTrue()
    }

    @Test
    fun `the period may not end before it starts`() {
        assertThrows<IllegalArgumentException> { DuplicateControl.scan(dataset(), DAY, DAY.minusDays(1)) }
    }
}
