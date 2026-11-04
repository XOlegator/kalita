// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kalita.core.RateService
import kalita.core.SpendingSummaryService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * `get_spending_summary` (dev_plan.md §12.13): wraps [SpendingSummaryService] — income/expense
 * totals by budget item for a period, defaulting to the current month like the journal's own
 * default filter ([kalita.core.JournalFilter.CURRENT_MONTH]). This is the one piece of §12.8's
 * level-1 analytics implemented ahead of release 1.5's `insights` plugin, by the user's own
 * decision not to wait for it — period-to-period comparison, trends and the rest of §12.8 are not
 * in scope here.
 */
class GetSpendingSummaryTool(private val dataset: Dataset) {
    fun execute(
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null,
        reductionCurrencyId: Long? = RateService.of(dataset).baseCurrencyId,
        today: LocalDate = LocalDate.now(),
    ): JsonObject {
        val rates = RateService.of(dataset)
        val summary = SpendingSummaryService.compute(dataset, rates, dateFrom, dateTo, reductionCurrencyId, today)
        return buildJsonObject {
            putDate("dateFrom", dateFrom)
            putDate("dateTo", dateTo)
            put(
                "byBudgetItem",
                JsonArray(
                    summary.byBudgetItem.map { line ->
                        buildJsonObject {
                            put("budgetItem", dataset.budgetItem(line.budgetItemId)?.name)
                            putAmount("income", line.income)
                            putAmount("expense", line.expense)
                            putAmount("net", line.net)
                            put("count", line.count)
                        }
                    },
                ),
            )
            put(
                "totals",
                buildJsonObject {
                    putAmount("income", summary.totalIncome)
                    putAmount("expense", summary.totalExpense)
                    putAmount("net", summary.totalNet)
                },
            )
            put("transferCount", summary.transferCount)
            put(
                "currenciesWithoutRate",
                JsonArray(summary.currenciesWithoutRate.map { JsonPrimitive(dataset.currency(it)?.name) }),
            )
        }
    }
}
