// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QAbstractItemModel
import io.qt.core.QModelIndex
import io.qt.core.Qt
import kalita.core.BudgetItem
import kalita.core.BudgetTree
import kalita.presentation.Strings

/**
 * Tree model backed by [BudgetTree]. Each node is a [BudgetItem]; the tree is rebuilt on
 * [setTree]. Internal pointers store the parent item's id (0 for roots).
 *
 * Shared by [BudgetTreeBrowser] (editable dictionary view) and [BudgetItemPicker] (single-select
 * field in [OperationFormDialog]) — one model, one set of expand/collapse semantics.
 */
class BudgetTreeModel : QAbstractItemModel() {
    private var tree = BudgetTree(emptyList())

    /** When set, only [scopeRootId]'s own subtree is shown — [scopeRootId] itself stays hidden,
     * its children become the view's top level. Used by [BudgetItemPicker] to restrict the field
     * to the current operation type's branch (`TypeOper.GroupBudget_ID`, dev_plan.md §9.7's
     * [kalita.core.OperationType.budgetRootId]) — the root of the "Приход"/"Расход" split itself
     * is never a pickable статья. */
    private var scopeRootId: Long? = null

    private val effectiveRoots: List<BudgetItem> get() = scopeRootId?.let(tree::children) ?: tree.roots

    /** Rebuilds the model from [newTree], optionally scoped to [scopeRootId]'s subtree. */
    fun setTree(newTree: BudgetTree, scopeRootId: Long? = null) {
        beginResetModel()
        tree = newTree
        this.scopeRootId = scopeRootId
        endResetModel()
    }

    fun itemAt(index: QModelIndex): BudgetItem? {
        if (!index.isValid) return null
        val id = index.internalId()
        val siblings = if (id == 0L) effectiveRoots else tree.children(id)
        return siblings.getOrNull(index.row())
    }

    override fun index(row: Int, column: Int, parent: QModelIndex?): QModelIndex {
        val parentId = if (parent == null || !parent.isValid) {
            0L
        } else {
            val parentItem = itemAt(parent) ?: return QModelIndex()
            parentItem.id
        }
        return createIndex(row, column, parentId)
    }

    override fun parent(child: QModelIndex?): QModelIndex {
        if (child == null || !child.isValid) return QModelIndex()
        val parentId = child.internalId()
        if (parentId == 0L || parentId == scopeRootId) return QModelIndex()
        val parentItem = tree.item(parentId) ?: return QModelIndex()
        val grandParentId = parentItem.parentId?.takeIf { tree.item(it) != null && it != scopeRootId }
        val siblings = if (grandParentId == null) effectiveRoots else tree.children(grandParentId)
        val row = siblings.indexOfFirst { it.id == parentId }
        if (row < 0) return QModelIndex()
        return createIndex(row, 0, grandParentId ?: 0L)
    }

    override fun rowCount(parent: QModelIndex?): Int {
        if (parent == null || !parent.isValid) return effectiveRoots.size
        val item = itemAt(parent) ?: return 0
        return tree.children(item.id).size
    }

    override fun columnCount(parent: QModelIndex?): Int = 1

    override fun data(index: QModelIndex, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole) return null
        val item = itemAt(index) ?: return null
        val suffix = if (item.deleted) " [удалён]" else ""
        return (item.name ?: "") + suffix
    }

    override fun headerData(section: Int, orientation: Qt.Orientation, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole || orientation != Qt.Orientation.Horizontal) return null
        return if (section == 0) Strings.NAME else null
    }

    /**
     * The index path from the view's top down to [id]'s own index — empty if [id] is unknown, or
     * lies outside the current [scopeRootId] subtree.
     */
    fun indexPath(id: Long): List<QModelIndex> {
        val fullPath = tree.path(id)
        val items = if (scopeRootId == null) {
            fullPath
        } else {
            val cut = fullPath.indexOfFirst { it.id == scopeRootId }
            if (cut < 0) return emptyList()
            fullPath.subList(cut + 1, fullPath.size)
        }
        val path = ArrayList<QModelIndex>(items.size)
        var parent = QModelIndex()
        for (item in items) {
            val siblings = if (!parent.isValid) effectiveRoots else tree.children(itemAt(parent)!!.id)
            val row = siblings.indexOfFirst { it.id == item.id }
            if (row < 0) return emptyList()
            parent = index(row, 0, parent)
            path += parent
        }
        return path
    }
}
