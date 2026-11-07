// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.core.Aggregate
import kalita.core.JournalRow
import kalita.core.aggregate
import java.math.BigDecimal
import java.time.LocalDate

/** One day's slice of the Android journal (FR-A2: "список операций по дням с итогами дня"). */
data class JournalDayGroup(val date: LocalDate?, val rows: List<JournalRow>, val total: BigDecimal?)

/**
 * Groups journal rows into one entry per day, in the order they already come in — so [rows] must
 * already be sorted by date (e.g. via [kalita.core.JournalPresenter.setSort]) for same-day rows to
 * land in one group instead of splitting across several. [JournalRow.values]'s `convertedAmount`
 * is already in the journal's reduction currency (dev_plan.md §5.2), so the day's total is a plain
 * [aggregate] sum over it, not a per-currency breakdown — same as the desktop grid's own footer.
 * `null` total (via [aggregate]'s own "empty column, empty cell" rule) means every row that day is
 * missing a rate (§5.5), not a real zero.
 */
fun groupByDay(rows: List<JournalRow>): List<JournalDayGroup> {
    val groups = LinkedHashMap<LocalDate?, MutableList<JournalRow>>()
    for (row in rows) {
        groups.getOrPut(row.operation.date) { mutableListOf() }.add(row)
    }
    return groups.map { (date, dayRows) ->
        JournalDayGroup(date, dayRows, aggregate(dayRows.map { it.values.convertedAmount }, Aggregate.Sum))
    }
}
