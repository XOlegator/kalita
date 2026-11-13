// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.core.Account
import kalita.core.BudgetItem
import kalita.core.Currency
import kalita.core.Dataset
import kalita.core.Operation
import kalita.core.Rate
import kalita.core.OperationType
import kalita.plugin.api.BankTransaction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

internal const val CARD = 10L
internal const val CASH = 11L
internal const val RSD = 20L
internal const val EUR = 21L
internal const val EXPENSE = 2L
internal const val INCOME = 1L
internal const val TRANSFER = 8L

internal fun op(
    id: Long, date: String, amount: String, type: Long = EXPENSE, from: Long? = CARD, to: Long? = null,
    currency: Long = RSD, item: Long? = 11, comment: String? = null, who: Long? = null, deleted: Boolean = false,
) = Operation(id, LocalDate.parse(date), BigDecimal(amount), currency, item, type, comment, who, from, to, deleted)

internal fun tx(id: String, date: String, amount: String, description: String = "MAXI 123, BEOGRAD", currency: String = "RSD", posted: Boolean = true) =
    BankTransaction(
        "apibank", id, "IVAN", LocalDate.parse(date), null, BigDecimal(amount), currency,
        description = description, merchant = description.substringBefore(','),
        status = if (posted) BankTransaction.Status.Posted else BankTransaction.Status.Pending,
    )

internal fun dataset(vararg ops: Operation, rates: List<Rate> = emptyList()) = Dataset(
    operations = ops.toList(),
    rates = rates,
    operationTypes = listOf(OperationType(INCOME, "Приход", 1, 1), OperationType(EXPENSE, "Расход", 2, -1), OperationType(TRANSFER, "Перемещение", 72, 0)),
    budgetItems = listOf(BudgetItem(1, null, "Приход", 1), BudgetItem(2, null, "Расход", 2), BudgetItem(11, 2, "Продукты", 1), BudgetItem(44, 11, "Еда", 1)),
    accounts = listOf(Account(CARD, "Олег API Bank"), Account(CASH, "Наличные")),
    currencies = listOf(Currency(RSD, "Динар", null), Currency(EUR, "Евро", null)),
)

class ReconcilerTest {
    private suspend fun reconcile(ds: Dataset, vararg txs: BankTransaction, linkedTx: Set<String> = emptySet(), linkedRows: Set<Long> = emptySet()) =
        Reconciler(ds, categorizer = HistoryCategorizer(ds)).reconcile(txs.toList(), CARD, RSD, linkedTx, linkedRows)

    @Test
    fun `one row with the same amount inside the window is an exact match, nearest date first`() = runTest {
        val ds = dataset(op(1, "2026-11-04", "1650"), op(2, "2026-11-08", "1650"), op(3, "2026-10-01", "1650"))

        val report = reconcile(ds, tx("a", "2026-11-09", "-1650.00"))

        val exact = report.proposals.single().shouldBeInstanceOf<Proposal.Exact>()
        exact.operation.id shouldBe 2
        exact.alternatives.map { it.id } shouldBe listOf(1)
    }

    @Test
    fun `a receipt split across rows of one day matches the bank line as a sum`() = runTest {
        val ds = dataset(
            op(1, "2026-11-07", "1000.00", item = 11), op(2, "2026-11-07", "500.00", item = 44), op(3, "2026-11-07", "150.00", item = 44),
            op(4, "2026-11-07", "150.00", item = 11, from = CASH), // other account, must not join the group
            op(5, "2026-11-06", "99.00"),
        )

        val report = reconcile(ds, tx("a", "2026-11-09", "-1650.00"))

        val sum = report.proposals.single().shouldBeInstanceOf<Proposal.Sum>()
        sum.operations.map { it.id }.sorted() shouldBe listOf(1, 2, 3)
        report.notInBank.map { it.id } shouldBe listOf(5)
    }

    @Test
    fun `a row in another currency matches by rate within tolerance, and a same-currency row ranks first`() = runTest {
        // RSD is the base (no rates); 1 EUR = 117.2 RSD. 14 EUR ≈ 1640.8 RSD, the bank says 1650 (0.56 % off).
        val rates = listOf(Rate(1, EUR, LocalDate.parse("2026-11-01"), 117.2))
        val ds = dataset(op(1, "2026-11-08", "14.00", currency = EUR), op(2, "2026-11-04", "1650"), rates = rates)

        val report = reconcile(ds, tx("a", "2026-11-09", "-1650.00"))

        val exact = report.proposals.single().shouldBeInstanceOf<Proposal.Exact>()
        exact.operation.id shouldBe 2
        exact.alternatives.map { it.id } shouldBe listOf(1)
    }

    @Test
    fun `a converted row outside tolerance, without rates, or with tolerance disabled is not a match`() = runTest {
        val rates = listOf(Rate(1, EUR, LocalDate.parse("2026-11-01"), 117.2))
        val far = dataset(op(1, "2026-11-08", "13.00", currency = EUR), rates = rates) // 1523.6 vs 1650: 7 % off
        reconcile(far, tx("a", "2026-11-09", "-1650.00")).proposals.single().shouldBeInstanceOf<Proposal.New>()

        val noRates = dataset(op(1, "2026-11-08", "14.00", currency = EUR))
        reconcile(noRates, tx("a", "2026-11-09", "-1650.00")).proposals.single().shouldBeInstanceOf<Proposal.New>()

        val near = dataset(op(1, "2026-11-08", "14.00", currency = EUR), rates = rates)
        Reconciler(near, ReconcileConfig(rateTolerance = -1.0))
            .reconcile(listOf(tx("a", "2026-11-09", "-1650.00")), CARD, RSD, emptySet(), emptySet())
            .proposals.single().shouldBeInstanceOf<Proposal.New>()
    }

    @Test
    fun `two identical bank lines take two different journal rows`() = runTest {
        val ds = dataset(op(1, "2026-11-09", "700"), op(2, "2026-11-09", "700"))

        val report = reconcile(ds, tx("a", "2026-11-09", "-700.00"), tx("b", "2026-11-09", "-700.00"))

        report.exact.map { it.operation.id } shouldBe listOf(1, 2)
    }

    @Test
    fun `nothing on the mapped account yields a draft, categorized from history, and flags the same amount elsewhere`() = runTest {
        val ds = dataset(
            op(1, "2026-10-01", "300", comment = "MAXI 123, BEOGRAD", item = 44, who = 4),
            op(2, "2026-10-02", "310", comment = "MAXI 123, BEOGRAD", item = 44, who = 4),
            op(3, "2026-11-08", "1650", from = CASH, comment = "по ошибке на наличные"),
        )

        val report = reconcile(ds, tx("a", "2026-11-09", "-1650.00"))

        val new = report.proposals.single().shouldBeInstanceOf<Proposal.New>()
        new.draft.amount shouldBe BigDecimal("1650.00")
        new.draft.date shouldBe LocalDate.parse("2026-11-09")
        new.draft.typeId shouldBe EXPENSE
        new.draft.accountFrom shouldBe CARD
        new.draft.accountTo shouldBe null
        new.draft.currencyId shouldBe RSD
        new.draft.budgetItemId shouldBe 44
        new.draft.counterpartyId shouldBe 4
        new.draft.comment shouldBe "MAXI 123, BEOGRAD"
        new.similarElsewhere.map { it.id } shouldBe listOf(3)
    }

    @Test
    fun `income lines look at the account-to side and the rows outside the window are not candidates`() = runTest {
        val ds = dataset(
            op(1, "2026-11-05", "691.92", type = INCOME, from = null, to = CARD, currency = EUR),
            op(2, "2026-10-01", "691.92", type = INCOME, from = null, to = CARD, currency = EUR),
        )

        val report = Reconciler(ds).reconcile(listOf(tx("a", "2026-11-05", "691.92", "ABATURION 2, Mildenhall", "EUR")), CARD, EUR, emptySet(), emptySet())

        report.exact.single().operation.id shouldBe 1
        report.notInBank.shouldBeEmpty()
    }

    @Test
    fun `a transfer out of the card counts as a candidate for an outgoing bank line`() = runTest {
        val ds = dataset(op(1, "2026-11-09", "5000", type = TRANSFER, from = CARD, to = CASH, item = 72))

        val report = reconcile(ds, tx("a", "2026-11-10", "-5000.00", "ATM WITHDRAWAL"))

        report.exact.single().operation.id shouldBe 1
    }

    @Test
    fun `already linked lines and rows, pending holds and deleted rows are skipped`() = runTest {
        val ds = dataset(op(1, "2026-11-09", "700"), op(2, "2026-11-09", "700", deleted = true), op(3, "2026-11-09", "800"))

        val report = reconcile(
            ds,
            tx("linked", "2026-11-09", "-700.00"), tx("hold", "2026-11-09", "-800.00", posted = false), tx("new", "2026-11-09", "-700.00"),
            linkedTx = setOf("apibank|linked"), linkedRows = setOf(3),
        )

        report.skippedAlreadyLinked shouldBe 1
        report.skippedPending shouldBe 1
        report.exact.single().operation.id shouldBe 1
        report.notInBank.shouldBeEmpty()
    }

    @Test
    fun `an empty statement reports nothing`() = runTest {
        val report = reconcile(dataset(op(1, "2026-11-09", "700")))
        report.proposals.shouldBeEmpty()
        report.notInBank.shouldBeEmpty()
        report.period shouldBe null
    }

    @Test
    fun `the history categorizer falls back from the exact comment to the merchant`() = runTest {
        val ds = dataset(op(1, "2026-10-01", "300", comment = "Maxi 123, NOVI SAD", item = 44, who = 4))
        val c = HistoryCategorizer(ds)

        c.suggest("MAXI 123, BEOGRAD", "MAXI 123", null).single().confidence shouldBe 0.6f
        c.suggest("maxi 123,  novi sad", null, null).single().confidence shouldBe 0.9f
        c.suggest("Unknown shop", null, null).shouldBeEmpty()
        CategorizerChain(listOf(c)).suggest("MAXI 123, X", null, null) shouldHaveSize 1
    }
}
