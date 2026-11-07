// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.core.DateAnchor
import kalita.core.DateBound
import kalita.core.DateCondition
import kalita.core.DateInterval
import kalita.core.JournalFilter
import kalita.core.RefCondition
import kalita.core.RefField

/** The journal's period chip (FR-A2); unlike the desktop «Фильтр» panel's free date editor, the
 * phone gets a fixed, single-select set of presets — a deliberate scope cut for this slice. */
enum class PeriodChip { Today, Week, Month, Quarter, Year, All }

/**
 * What the journal's filter chips (FR-A2: «период, тип, счёт, статья, кто/кому») currently have
 * selected, independent of [kalita.presentation.JournalState]'s own `textFilter`/`showDeleted` —
 * those two map onto [kalita.presentation.JournalPresenter] directly and need no chip state of
 * their own. An empty id set for a field means "any" (no [RefCondition] for it at all), same as an
 * unchecked field in the desktop panel.
 *
 * Budget items are offered flat, with no subtree/"Показывать подуровни" drill-down (FR-A6 already
 * accepts the same cut for the tree browser itself in 1.2) — picking a leaf-less parent category
 * on the phone matches only operations posted directly to it, not its children.
 */
data class JournalChipSelection(
    val period: PeriodChip = PeriodChip.Month,
    val typeIds: Set<Long> = emptySet(),
    val accountIds: Set<Long> = emptySet(),
    val budgetItemIds: Set<Long> = emptySet(),
    val counterpartyIds: Set<Long> = emptySet(),
) {
    fun toFilter(): JournalFilter {
        val conditions = buildList {
            periodInterval(period)?.let { add(DateCondition(listOf(it))) }
            if (typeIds.isNotEmpty()) add(RefCondition(RefField.OperationType, typeIds))
            if (accountIds.isNotEmpty()) add(RefCondition(RefField.Account, accountIds))
            if (budgetItemIds.isNotEmpty()) add(RefCondition(RefField.BudgetItem, budgetItemIds))
            if (counterpartyIds.isNotEmpty()) add(RefCondition(RefField.Counterparty, counterpartyIds))
        }
        return JournalFilter(conditions)
    }

    private fun periodInterval(period: PeriodChip): DateInterval? = when (period) {
        PeriodChip.Today -> DateInterval.TODAY
        PeriodChip.Week -> DateInterval(DateBound.Relative(DateAnchor.WeekStart), DateBound.Relative(DateAnchor.WeekEnd))
        PeriodChip.Month -> DateInterval.CURRENT_MONTH
        PeriodChip.Quarter -> DateInterval.CURRENT_QUARTER
        PeriodChip.Year -> DateInterval(DateBound.Relative(DateAnchor.YearStart), DateBound.Relative(DateAnchor.YearEnd))
        PeriodChip.All -> null
    }
}
