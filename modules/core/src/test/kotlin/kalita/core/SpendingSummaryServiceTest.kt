// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kalita.core.DemoData.EXCHANGE_IN
import kalita.core.DemoData.EXCHANGE_OUT
import kalita.core.DemoData.OPENING_BALANCE
import kalita.core.DemoData.RUB
import kalita.core.DemoData.USD
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

class SpendingSummaryServiceTest {
    private val dataset = DemoData.dataset
    private val rates = RateService.of(dataset)

    private fun BigDecimal.cents() = setScale(2, RoundingMode.HALF_UP)

    @Test
    fun `transfers between own accounts are excluded, by budget item otherwise`() {
        // Every DemoData operation falls in January 2006 — an explicit range covering the whole
        // month, so this does not rely on the "current month" default (dev_plan.md §5.1 demo data).
        val summary = SpendingSummaryService.compute(
            dataset, rates, dateFrom = LocalDate.of(2006, 1, 1), dateTo = LocalDate.of(2006, 1, 31), reductionCurrencyId = RUB,
        )

        // Three TRANSFER operations in the fixture (MAIN<->HUSBAND twice, HUSBAND->CHILD) — none of
        // them land in byBudgetItem or either total.
        summary.transferCount shouldBe 3
        summary.currenciesWithoutRate shouldBe emptySet()

        summary.byBudgetItem.map { listOf(it.budgetItemId, it.income.cents(), it.expense.cents(), it.count) } shouldContainExactlyInAnyOrder listOf(
            // Currency exchange: a RUB income leg and a USD expense leg, deliberately not merged or excluded — see this service's own KDoc.
            listOf(EXCHANGE_IN, BigDecimal("2860.00"), BigDecimal("0.00"), 1),
            listOf(EXCHANGE_OUT, BigDecimal("0.00"), BigDecimal("-2798.05"), 1), // 100 USD * rate(USD, jan26)=27.9805
            // Five opening-balance Приход operations, three currencies/accounts, one budget item.
            listOf(OPENING_BALANCE, BigDecimal("86782.50"), BigDecimal("0.00"), 5),
        )

        summary.totalIncome.cents() shouldBe BigDecimal("89642.50")
        summary.totalExpense.cents() shouldBe BigDecimal("-2798.05")
        summary.totalNet.cents() shouldBe BigDecimal("86844.45")
    }

    @Test
    fun `a soft-deleted operation never counts`() {
        // DemoData.deletedOperation is a 999 RUB expense from Wife, soft-deleted — JournalQuery's
        // own showDeleted=false default already excludes it, same as every other read tool.
        val summary = SpendingSummaryService.compute(
            dataset, rates, dateFrom = LocalDate.of(2006, 1, 1), dateTo = LocalDate.of(2006, 1, 31), reductionCurrencyId = RUB,
        )

        summary.byBudgetItem.sumOf { it.count } shouldBe 7 // 2 exchange legs + 5 opening balances, not 8
    }

    @Test
    fun `no explicit range defaults to the current month`() {
        val summary = SpendingSummaryService.compute(
            dataset, rates, dateFrom = null, dateTo = null, reductionCurrencyId = RUB, today = LocalDate.of(2006, 1, 15),
        )

        summary.totalIncome.cents() shouldBe BigDecimal("89642.50")

        val outsideJanuary = SpendingSummaryService.compute(
            dataset, rates, dateFrom = null, dateTo = null, reductionCurrencyId = RUB, today = LocalDate.of(2006, 2, 15),
        )
        outsideJanuary.byBudgetItem shouldBe emptyList()
        outsideJanuary.transferCount shouldBe 0
    }

    @Test
    fun `currencies without any rate are reported, not silently dropped from the picture`() {
        val data = Dataset(
            operations = listOf(Operation(1, DemoData.jan1, BigDecimal("7"), 999, DemoData.OPENING_BALANCE, DemoData.INCOME, accountTo = DemoData.MAIN)),
            operationTypes = dataset.operationTypes,
            budgetItems = dataset.budgetItems,
        )
        val summary = SpendingSummaryService.compute(
            data, RateService(emptyList(), RUB), dateFrom = LocalDate.of(2006, 1, 1), dateTo = LocalDate.of(2006, 1, 31), reductionCurrencyId = RUB,
        )

        summary.byBudgetItem shouldBe emptyList()
        summary.currenciesWithoutRate shouldBe setOf(999L)
    }

    @Test
    fun `an operation type with no PlusMinus at all falls back to income, like OperationType sign`() {
        val typesWithUnset = dataset.operationTypes + OperationType(id = 900, name = "?", budgetRootId = null, plusMinus = null)
        val data = Dataset(
            operations = listOf(Operation(1, DemoData.jan1, BigDecimal("50"), RUB, DemoData.OPENING_BALANCE, 900, accountTo = DemoData.MAIN)),
            operationTypes = typesWithUnset,
            budgetItems = dataset.budgetItems,
        )
        val summary = SpendingSummaryService.compute(
            data, RateService.of(data, RUB), dateFrom = LocalDate.of(2006, 1, 1), dateTo = LocalDate.of(2006, 1, 31), reductionCurrencyId = RUB,
        )

        summary.byBudgetItem.single().income shouldBe BigDecimal("50")
        summary.byBudgetItem.single().expense shouldBe BigDecimal.ZERO
    }

    @Test
    fun `USD leg of the currency exchange converts at the rate on its own date`() {
        val summary = SpendingSummaryService.compute(
            dataset, rates, dateFrom = LocalDate.of(2006, 1, 1), dateTo = LocalDate.of(2006, 1, 31), reductionCurrencyId = USD,
        )

        // Reduction in USD instead of RUB: the exchange's own USD leg is now untouched (rate 1), the
        // RUB legs convert by dividing by rate(USD) on their date — the same cross-rate JournalColumns
        // already uses, exercised here instead of only RUB-as-base.
        val exchangeOut = summary.byBudgetItem.single { it.budgetItemId == EXCHANGE_OUT }
        exchangeOut.expense shouldBe BigDecimal("-100")
    }
}
