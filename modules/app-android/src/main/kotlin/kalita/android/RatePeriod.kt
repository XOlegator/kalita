// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.core.Rate
import java.time.LocalDate

/** The periods «Загрузить из Интернета» offers — a tap instead of two date pickers for the usual cases. */
enum class RatePeriodChoice { Today, Week, Month, SinceLast, Custom }

/**
 * Where rates stop for the currencies that have any: the earliest of their latest dates, i.e. the
 * day from which at least one currency has a gap. Only [currencyIds] count — the live currencies;
 * the rates of a deleted currency say nothing about what is missing. `null` when none has a rate yet.
 */
internal fun lastCoveredRateDate(rates: List<Rate>, currencyIds: Set<Long>): LocalDate? =
    rates.filter { !it.deleted && it.currencyId in currencyIds && it.date != null }
        .groupBy { it.currencyId }
        .mapNotNull { (_, list) -> list.maxOf { it.date!! } }
        .minOrNull()

/** The period a choice stands for; `null` for [RatePeriodChoice.Custom] (the user picks) and for [RatePeriodChoice.SinceLast] without rates. */
internal fun ratePeriod(choice: RatePeriodChoice, today: LocalDate, rates: List<Rate>, currencyIds: Set<Long>): Pair<LocalDate, LocalDate>? = when (choice) {
    RatePeriodChoice.Today -> today to today
    RatePeriodChoice.Week -> today.minusDays(6) to today
    RatePeriodChoice.Month -> today.minusDays(29) to today
    RatePeriodChoice.SinceLast -> lastCoveredRateDate(rates, currencyIds)?.takeIf { !it.isAfter(today) }?.let { it to today }
    RatePeriodChoice.Custom -> null
}
