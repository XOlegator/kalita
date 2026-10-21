// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

/**
 * Read-only view of the budget item tree (`GroupBudget.Parent_ID`, dev_plan.md §5.4): siblings in
 * `Ord` order, then by `id`; a missing `Ord` sorts last. Soft-deleted items are part of the tree —
 * hiding them is up to the caller.
 *
 * The file declares no foreign keys, so the tree is taken as found: an item whose parent does not
 * exist is treated as a root, and a parent cycle is cut where it closes instead of looping.
 */
class BudgetTree(items: List<BudgetItem>) {
    private val byId = items.associateBy { it.id }
    private val children: Map<Long?, List<BudgetItem>> = items
        .groupBy { item -> item.parentId?.takeIf { it in byId && it != item.id } }
        .mapValues { (_, list) -> list.sortedWith(siblingOrder) }

    /** Top-level items in display order. */
    val roots: List<BudgetItem> get() = children[null].orEmpty()

    fun item(id: Long?): BudgetItem? = id?.let(byId::get)

    fun children(id: Long): List<BudgetItem> = children[id].orEmpty()

    /** [ids] and everything below them; unknown ids are kept as they are. */
    fun withDescendants(ids: Collection<Long>): Set<Long> {
        val result = LinkedHashSet<Long>()
        val pending = ArrayDeque(ids)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (result.add(id)) children[id]?.forEach { pending.addLast(it.id) }
        }
        return result
    }

    /** From the root down to the item itself; empty for an unknown id. */
    fun path(id: Long): List<BudgetItem> {
        val path = ArrayList<BudgetItem>()
        val seen = HashSet<Long>()
        var current = byId[id]
        while (current != null && seen.add(current.id)) {
            path += current
            current = current.parentId?.let(byId::get)
        }
        return path.asReversed()
    }

    /** Every item in display order, depth-first — the order of the tree on screen. */
    fun preorder(): List<BudgetItem> {
        val result = ArrayList<BudgetItem>(byId.size)
        val seen = HashSet<Long>()
        fun visit(item: BudgetItem) {
            if (!seen.add(item.id)) return
            result += item
            children[item.id]?.forEach(::visit)
        }
        roots.forEach(::visit)
        // Items on a parent cycle have no root above them; list them after the reachable tree.
        byId.values.sortedWith(siblingOrder).forEach(::visit)
        return result
    }

    private companion object {
        val siblingOrder: Comparator<BudgetItem> =
            compareBy<BudgetItem, Long?>(nullsLast()) { it.ord }.thenBy { it.id }
    }
}
