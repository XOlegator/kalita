// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.core.BudgetItem
import kalita.core.Dataset
import java.time.LocalDate

/** The [limit] most recently used distinct, non-deleted budget items in the journal, newest first (FR-A3 «недавние»). */
fun recentBudgetItems(dataset: Dataset, limit: Int = 5): List<BudgetItem> =
    dataset.operations.asSequence()
        .filter { !it.deleted }
        .sortedByDescending { it.date ?: LocalDate.MIN }
        .mapNotNull { it.budgetItemId?.let(dataset::budgetItem) }
        .filter { !it.deleted }
        .distinctBy { it.id }
        .take(limit)
        .toList()

/**
 * The [limit] budget items used by the most non-deleted operations (FR-A3 «частые»), most used
 * first; ties go to the more recently used one. Items already shown as [exclude] (the «недавние»
 * row) are skipped so the two rows do not repeat each other.
 */
fun frequentBudgetItems(dataset: Dataset, limit: Int = 5, exclude: Set<Long> = emptySet()): List<BudgetItem> {
    val uses = dataset.operations.asSequence()
        .filter { !it.deleted && it.budgetItemId != null }
        .groupBy { it.budgetItemId!! }
    return uses.entries
        .mapNotNull { (id, ops) -> dataset.budgetItem(id)?.takeIf { !it.deleted && it.id !in exclude }?.let { it to ops } }
        .sortedWith(
            compareByDescending<Pair<BudgetItem, List<kalita.core.Operation>>> { it.second.size }
                .thenByDescending { pair -> pair.second.maxOf { it.date ?: LocalDate.MIN } },
        )
        .take(limit)
        .map { it.first }
}
