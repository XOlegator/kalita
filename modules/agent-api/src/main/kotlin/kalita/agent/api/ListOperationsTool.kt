// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.AmountCondition
import kalita.core.Dataset
import kalita.core.DateBound
import kalita.core.DateCondition
import kalita.core.DateInterval
import kalita.core.FilterCondition
import kalita.core.JournalFilter
import kalita.core.JournalQuery
import kalita.core.RateService
import kalita.core.RefCondition
import kalita.core.RefField
import kalita.core.TextCondition
import kalita.core.TextField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

/**
 * `list_operations` (dev_plan.md §12.13): wraps [JournalQuery] with plain parameters instead of the
 * filter panel's full JSON — the caller (the CLI today) resolves names to ids via [ResolveTool]
 * first, the same split of responsibility as [AddOperationsTool].
 */
class ListOperationsTool(private val dataset: Dataset) {
    fun execute(
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null,
        budgetItemIds: Set<Long> = emptySet(),
        accountIds: Set<Long> = emptySet(),
        currencyIds: Set<Long> = emptySet(),
        counterpartyIds: Set<Long> = emptySet(),
        typeIds: Set<Long> = emptySet(),
        amountMin: BigDecimal? = null,
        amountMax: BigDecimal? = null,
        commentSubstring: String? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_LIMIT,
        reductionCurrencyId: Long? = RateService.of(dataset).baseCurrencyId,
        today: LocalDate = LocalDate.now(),
    ): JsonObject {
        val conditions = buildList<FilterCondition> {
            if (dateFrom != null || dateTo != null) {
                add(DateCondition(listOf(DateInterval(dateFrom?.let(DateBound::Fixed), dateTo?.let(DateBound::Fixed)))))
            }
            if (budgetItemIds.isNotEmpty()) add(RefCondition(RefField.BudgetItem, budgetItemIds, withDescendants = true))
            if (accountIds.isNotEmpty()) add(RefCondition(RefField.Account, accountIds))
            if (currencyIds.isNotEmpty()) add(RefCondition(RefField.Currency, currencyIds))
            if (counterpartyIds.isNotEmpty()) add(RefCondition(RefField.Counterparty, counterpartyIds))
            if (typeIds.isNotEmpty()) add(RefCondition(RefField.OperationType, typeIds))
            if (amountMin != null || amountMax != null) add(AmountCondition(amountMin, amountMax))
            if (!commentSubstring.isNullOrBlank()) add(TextCondition(TextField.Comment, commentSubstring))
        }
        val rates = RateService.of(dataset)
        val query = JournalQuery(dataset, rates)
        val rows = query.run(JournalFilter(conditions), today = today, reductionCurrencyId = reductionCurrencyId)
        val cappedLimit = limit.coerceIn(1, MAX_LIMIT)
        val page = rows.drop(offset.coerceAtLeast(0)).take(cappedLimit)

        var sumSigned = BigDecimal.ZERO
        var sumConverted = BigDecimal.ZERO
        for (row in rows) {
            row.values.signedAmount?.let { sumSigned += it }
            row.values.convertedAmount?.let { sumConverted += it }
        }

        return buildJsonObject {
            put("totalCount", rows.size)
            put("offset", offset)
            put("limit", cappedLimit)
            put(
                "rows",
                JsonArray(
                    page.map { row ->
                        val op = row.operation
                        buildJsonObject {
                            put("id", op.id)
                            putDate("date", op.date)
                            putAmount("amount", row.values.signedAmount)
                            putAmount("convertedAmount", row.values.convertedAmount)
                            put("currency", dataset.currency(op.currencyId)?.name)
                            put("budgetItem", dataset.budgetItem(op.budgetItemId)?.name)
                            put("operationType", dataset.operationType(op.typeId)?.name)
                            put("counterparty", dataset.counterparty(op.counterpartyId)?.name)
                            put("accountFrom", dataset.account(op.accountFrom)?.name)
                            put("accountTo", dataset.account(op.accountTo)?.name)
                            put("comment", op.comment)
                        }
                    },
                ),
            )
            put(
                "aggregate",
                buildJsonObject {
                    putAmount("sumAmount", sumSigned)
                    putAmount("sumConvertedAmount", sumConverted)
                },
            )
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}
