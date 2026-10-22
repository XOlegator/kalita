// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/**
 * Exchange rates on a date (dev_plan.md §5.2, §5.5). A rate is the value of one unit of a currency
 * in [baseCurrencyId]; the base currency itself is always 1.
 *
 * Rates are returned as [BigDecimal] made from the stored `Double` by its shortest decimal form
 * (`28.7825`, not `28.78249999…`), so amounts multiplied by them stay exact.
 */
class RateService(rates: List<Rate>, val baseCurrencyId: Long?) {
    // Per currency, live rates by date ascending; on the same date the later row (higher id) wins.
    // Kept as parallel arrays so a lookup is a binary search — the journal asks once per row.
    private val byCurrency: Map<Long, Quotes> = rates
        .filter { !it.deleted && it.currencyId != null && it.date != null && it.value != null }
        .groupBy { it.currencyId!! }
        .mapValues { (_, list) ->
            val sorted = list.sortedWith(compareBy<Rate>({ it.date }, { it.id }))
            Quotes(
                LongArray(sorted.size) { sorted[it].date!!.toEpochDay() },
                Array(sorted.size) { BigDecimal.valueOf(sorted[it].value!!) },
            )
        }

    private class Quotes(val days: LongArray, val values: Array<BigDecimal>) {
        /** The value of the last quote with day <= [day], `null` if all are later. */
        fun onOrBefore(day: Long): BigDecimal? {
            var lo = 0
            var hi = days.size // invariant: days[< lo] <= day < days[>= hi]
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (days[mid] <= day) lo = mid + 1 else hi = mid
            }
            return if (lo == 0) null else values[lo - 1]
        }
    }

    /**
     * The last rate with `Date <= date`, 1 for the base currency, `null` when the currency has no
     * rate on or before [date] (the journal then leaves the converted amount empty).
     */
    fun rate(currencyId: Long?, date: LocalDate?): BigDecimal? {
        if (currencyId == null) return null
        if (currencyId == baseCurrencyId) return BigDecimal.ONE
        val quotes = byCurrency[currencyId] ?: return null
        val day = date ?: return null
        return quotes.onOrBefore(day.toEpochDay())
    }

    /** The most recent rate of the currency regardless of date (the «Остатки» total, §5.3). */
    fun latest(currencyId: Long?): BigDecimal? = rate(currencyId, LocalDate.MAX)

    /**
     * Value of one unit of [currencyId] in [targetCurrencyId] on [date]:
     * `rate(currency, date) / rate(target, date)` (the journal's «Курс» column, §5.2).
     */
    fun cross(currencyId: Long?, targetCurrencyId: Long?, date: LocalDate?): BigDecimal? {
        val from = rate(currencyId, date) ?: return null
        val to = rate(targetCurrencyId, date) ?: return null
        return divide(from, to)
    }

    /** [cross] at the latest rates of both currencies. */
    fun latestCross(currencyId: Long?, targetCurrencyId: Long?): BigDecimal? =
        cross(currencyId, targetCurrencyId, LocalDate.MAX)

    private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal? = when {
        b.signum() == 0 -> null
        b.compareTo(BigDecimal.ONE) == 0 -> a
        else -> a.divide(b, MathContext.DECIMAL64)
    }

    companion object {
        /** Rates of [dataset] quoted in [baseCurrencyId], by default the currency without rates. */
        fun of(dataset: Dataset, baseCurrencyId: Long? = dataset.detectBaseCurrency()): RateService =
            RateService(dataset.rates, baseCurrencyId)
    }
}
