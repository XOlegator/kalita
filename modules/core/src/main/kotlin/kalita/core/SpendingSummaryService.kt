// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.time.LocalDate

/** One budget item's totals within a [SpendingSummary] (dev_plan.md §12.8's "структура по статьям"). */
data class BudgetItemSpending(
    val budgetItemId: Long?,
    /** Σ converted amount of this budget item's Приход operations. */
    val income: BigDecimal,
    /** Σ converted amount of this budget item's Расход operations — negative, same sign convention as [JournalValues.signedAmount]. */
    val expense: BigDecimal,
    val count: Int,
) {
    val net: BigDecimal get() = income.add(expense)
}

/** A period's income/expense totals, grouped by budget item, in the reduction currency. */
data class SpendingSummary(
    val byBudgetItem: List<BudgetItemSpending>,
    val totalIncome: BigDecimal,
    val totalExpense: BigDecimal,
    /** Transfers between the user's own accounts, excluded from every total above (dev_plan.md §12.8). */
    val transferCount: Int,
    /** Currencies left out of every total for lack of any rate to the reduction currency — mirrors [SaldoTotal.currenciesWithoutRate]. */
    val currenciesWithoutRate: Set<Long?>,
) {
    val totalNet: BigDecimal get() = totalIncome.add(totalExpense)
}

/**
 * Income/expense totals by budget item over a period (dev_plan.md §12.8's level-1 "structure by
 * budget item" — the one piece of §12.8's level 1-2 analytics implemented ahead of release 1.5's
 * `insights` plugin, for `get_spending_summary` (§12.13), by the user's own decision not to wait for
 * 1.5. Trends, seasonality, period-to-period comparison, anomalies, forecasts and budgets (the rest
 * of §12.8) still wait for that plugin — they need history and models this service does not build.
 *
 * Transfers between the user's own accounts are excluded, as §12.8 requires. Currency-exchange
 * operations are **not** excluded: telling them apart from an ordinary expense/income needs the
 * convention §5.1 leaves to a budget item's *name* ("Обмен валюты") rather than a structural
 * property of the data — recognising that properly is left to the full `insights` plugin.
 */
object SpendingSummaryService {
    fun compute(
        dataset: Dataset,
        rates: RateService,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        reductionCurrencyId: Long?,
        today: LocalDate = LocalDate.now(),
    ): SpendingSummary {
        val interval = if (dateFrom == null && dateTo == null) {
            DateInterval.CURRENT_MONTH
        } else {
            DateInterval(dateFrom?.let(DateBound::Fixed), dateTo?.let(DateBound::Fixed))
        }
        val rows = JournalQuery(dataset, rates).run(
            JournalFilter(listOf(DateCondition(listOf(interval)))),
            reductionCurrencyId = reductionCurrencyId,
            today = today,
        )

        val income = LinkedHashMap<Long?, BigDecimal>()
        val expense = LinkedHashMap<Long?, BigDecimal>()
        val count = LinkedHashMap<Long?, Int>()
        var transferCount = 0
        val withoutRate = LinkedHashSet<Long?>()
        for (row in rows) {
            val op = row.operation
            val kind = dataset.operationType(op.typeId)?.kind
            if (kind == OperationKind.Transfer) {
                transferCount++
                continue
            }
            val converted = row.values.convertedAmount
            if (converted == null) {
                withoutRate += op.currencyId
                continue
            }
            count.merge(op.budgetItemId, 1, Int::plus)
            // kind == null (no TypeOper.PlusMinus at all, an incomplete operation type) falls back
            // to "income", matching OperationType.sign's own null-handling convention of not
            // negating an amount it cannot classify.
            if (kind == OperationKind.Expense) {
                expense.merge(op.budgetItemId, converted, BigDecimal::add)
            } else {
                income.merge(op.budgetItemId, converted, BigDecimal::add)
            }
        }

        val byBudgetItem = (income.keys + expense.keys + count.keys).toSet()
            .sortedWith(compareBy({ it == null }, { it }))
            .map { id -> BudgetItemSpending(id, income[id] ?: BigDecimal.ZERO, expense[id] ?: BigDecimal.ZERO, count[id] ?: 0) }

        return SpendingSummary(
            byBudgetItem = byBudgetItem,
            totalIncome = income.values.fold(BigDecimal.ZERO, BigDecimal::add),
            totalExpense = expense.values.fold(BigDecimal.ZERO, BigDecimal::add),
            transferCount = transferCount,
            currenciesWithoutRate = withoutRate,
        )
    }
}
