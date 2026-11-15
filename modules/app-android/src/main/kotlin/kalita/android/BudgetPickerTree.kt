// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.core.BudgetItem
import kalita.core.BudgetTree

/** One visible line of the budget item picker tree. */
data class BudgetPickerRow(
    val item: BudgetItem,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

/**
 * Pure state of the collapsible budget item tree used by the phone's item picker (FR-A3):
 * which rows are visible for a given set of expanded ids, and for a search text. Deleted items
 * are never offered. While searching, every match is shown together with its ancestors (so a hit
 * keeps its context) and the matched branches are open; without a query only expanded branches
 * show their children.
 */
object BudgetPickerTree {
    /** Ids to open at first so that [selectedId] is visible: all its ancestors. */
    fun initiallyExpanded(items: List<BudgetItem>, selectedId: Long?): Set<Long> {
        if (selectedId == null) return emptySet()
        return BudgetTree(items.filter { !it.deleted }).path(selectedId).dropLast(1).mapTo(HashSet()) { it.id }
    }

    fun rows(items: List<BudgetItem>, expanded: Set<Long>, query: String = ""): List<BudgetPickerRow> {
        val tree = BudgetTree(items.filter { !it.deleted })
        val needle = query.trim().lowercase()
        val visible: Set<Long>? = if (needle.isEmpty()) null else {
            val keep = HashSet<Long>()
            for (item in tree.preorder()) {
                if (item.name?.lowercase()?.contains(needle) == true) tree.path(item.id).forEach { keep += it.id }
            }
            keep
        }
        val result = ArrayList<BudgetPickerRow>()
        fun visit(item: BudgetItem, depth: Int) {
            if (visible != null && item.id !in visible) return
            val kids = tree.children(item.id).filter { visible == null || it.id in visible }
            val open = if (visible != null) kids.isNotEmpty() else item.id in expanded
            result += BudgetPickerRow(item, depth, hasChildren = kids.isNotEmpty(), expanded = open && kids.isNotEmpty())
            if (open) kids.forEach { visit(it, depth + 1) }
        }
        tree.roots.forEach { visit(it, 0) }
        return result
    }

    fun toggle(expanded: Set<Long>, id: Long): Set<Long> = if (id in expanded) expanded - id else expanded + id
}
