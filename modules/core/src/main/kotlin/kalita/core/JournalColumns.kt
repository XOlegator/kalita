// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.math.MathContext

/** The computed columns of one journal row (dev_plan.md §5.2); `null` where an input is missing. */
data class JournalValues(
    /** «Сумма»: `Summa × (PlusMinus == 0 ? 1 : PlusMinus)`. */
    val signedAmount: BigDecimal?,
    /** «Курс»: one unit of the operation's currency in the reduction currency on the operation's date. */
    val rate: BigDecimal?,
    /** «Сумма в пересчёте»: `signedAmount × rate`, unrounded — rounding is the presentation's job. */
    val convertedAmount: BigDecimal?,
)

/**
 * Computes the journal's derived columns for a chosen reduction currency («Валюта приведения»).
 * Changing the reduction currency only needs a new [JournalColumns] over the same data (FR-23).
 */
class JournalColumns(
    private val dataset: Dataset,
    private val rates: RateService,
    val reductionCurrencyId: Long?,
) {
    fun values(operation: Operation): JournalValues {
        val sign = dataset.operationType(operation.typeId)?.sign ?: 1
        val signed = operation.amount?.multiply(BigDecimal(sign))
        val rate = rates.cross(operation.currencyId, reductionCurrencyId, operation.date)
        val converted = if (signed != null && rate != null) signed.multiply(rate) else null
        return JournalValues(signed, rate, converted)
    }
}

/** Footer functions of a journal column (FR-24). */
enum class Aggregate { Sum, Min, Max, Average, Count }

/**
 * Applies [function] to the non-null [values] — the footer is computed over the visible rows only
 * (dev_plan.md §5.2). [Aggregate.Count] counts non-empty cells; the others are `null` for an empty
 * column, as an empty footer cell.
 */
fun aggregate(values: List<BigDecimal?>, function: Aggregate): BigDecimal? {
    val present = values.filterNotNull()
    val sum by lazy { present.fold(BigDecimal.ZERO, BigDecimal::add) }
    return when {
        function == Aggregate.Count -> BigDecimal(present.size)
        present.isEmpty() -> null
        function == Aggregate.Sum -> sum
        function == Aggregate.Min -> present.min()
        function == Aggregate.Max -> present.max()
        else -> sum.divide(BigDecimal(present.size), MathContext.DECIMAL64)
    }
}
