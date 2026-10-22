// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

/**
 * Tree mutations for budget items (dev_plan.md §5.4): move up / move down among siblings
 * (swap or renumber `Ord`), cut-and-paste to another parent (`Parent_ID`), and protection of
 * the roots that anchor operation-type subtrees.
 *
 * Operates on an immutable [Dataset] — every method returns the field-level changes needed, not
 * a modified tree. The caller passes these changes to the storage layer (via `ChangeSetBuilder`,
 * once it exists) to persist them atomically.
 */
class BudgetTreeService(dataset: Dataset) {
    private val tree = BudgetTree(dataset.budgetItems)

    private val protectedIds: Set<Long> = dataset.operationTypes
        .filter { !it.deleted }
        .mapNotNullTo(HashSet()) { it.budgetRootId }

    /** Whether the item is a budget-item root of a live operation type and cannot be moved or deleted. */
    fun isProtected(id: Long): Boolean = id in protectedIds

    /** Move the item one position up among its siblings (`Ctrl+↑`, FR-11). */
    fun moveUp(id: Long): MoveResult {
        val item = tree.item(id) ?: return MoveResult.ItemNotFound
        if (isProtected(id)) return MoveResult.Protected
        val siblings = siblings(item)
        val index = siblings.indexOfFirst { it.id == id }
        if (index <= 0) return MoveResult.AlreadyAtBound
        return swap(siblings, index, index - 1)
    }

    /** Move the item one position down among its siblings (`Ctrl+↓`, FR-11). */
    fun moveDown(id: Long): MoveResult {
        val item = tree.item(id) ?: return MoveResult.ItemNotFound
        if (isProtected(id)) return MoveResult.Protected
        val siblings = siblings(item)
        val index = siblings.indexOfFirst { it.id == id }
        if (index < 0 || index >= siblings.lastIndex) return MoveResult.AlreadyAtBound
        return swap(siblings, index, index + 1)
    }

    /**
     * Move the item (with its entire subtree) to [newParentId] (`Ctrl+X` / `Ctrl+V`, FR-11).
     * Pass `null` to move to the root level ("Вставить в корень").
     */
    fun move(id: Long, newParentId: Long?): MoveResult {
        val item = tree.item(id) ?: return MoveResult.ItemNotFound
        if (isProtected(id)) return MoveResult.Protected
        if (item.parentId == newParentId) return MoveResult.SameParent
        if (newParentId != null) {
            tree.item(newParentId) ?: return MoveResult.ItemNotFound
            if (newParentId in tree.withDescendants(listOf(id))) return MoveResult.WouldCycle
        }
        val newSiblings = if (newParentId == null) tree.roots else tree.children(newParentId)
        val maxOrd = newSiblings.maxOfOrNull { it.ord ?: 0L } ?: 0L
        return MoveResult.Ok(
            listOf(
                TreeChange.ParentChanged(id, item.parentId, newParentId),
                TreeChange.OrdChanged(id, item.ord, maxOrd + 1),
            ),
        )
    }

    private fun siblings(item: BudgetItem): List<BudgetItem> {
        val parentId = item.parentId?.takeIf { tree.item(it) != null }
        return if (parentId == null) tree.roots else tree.children(parentId)
    }

    private fun swap(siblings: List<BudgetItem>, from: Int, to: Int): MoveResult {
        val a = siblings[from]
        val b = siblings[to]
        if (a.ord != null && b.ord != null && a.ord != b.ord) {
            return MoveResult.Ok(
                listOf(
                    TreeChange.OrdChanged(a.id, a.ord, b.ord),
                    TreeChange.OrdChanged(b.id, b.ord, a.ord),
                ),
            )
        }
        // Equal or null Ord values — renumber all siblings so the swap takes effect.
        val reordered = siblings.toMutableList().also {
            it[from] = b
            it[to] = a
        }
        val changes = reordered.mapIndexedNotNull { i, s ->
            val newOrd = (i + 1).toLong()
            if (s.ord != newOrd) TreeChange.OrdChanged(s.id, s.ord, newOrd) else null
        }
        return MoveResult.Ok(changes)
    }
}

/** A single field-level change to a budget item, produced by [BudgetTreeService]. */
sealed interface TreeChange {
    val itemId: Long

    data class OrdChanged(override val itemId: Long, val old: Long?, val new: Long) : TreeChange
    data class ParentChanged(override val itemId: Long, val old: Long?, val new: Long?) : TreeChange
}

/** Result of a [BudgetTreeService] mutation. */
sealed interface MoveResult {
    /** The changes to apply; may touch more than one item (e.g. an Ord swap). */
    data class Ok(val changes: List<TreeChange>) : MoveResult

    data object ItemNotFound : MoveResult
    data object Protected : MoveResult
    data object AlreadyAtBound : MoveResult
    data object WouldCycle : MoveResult
    data object SameParent : MoveResult
}
