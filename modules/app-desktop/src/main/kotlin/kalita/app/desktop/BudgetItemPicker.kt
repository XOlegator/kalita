// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.core.QModelIndex
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QPushButton
import io.qt.widgets.QTreeView
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.presentation.Strings

/**
 * Budget item field for [OperationFormDialog]: a collapsible tree, same shape as the original
 * X-Cash "Добавление" dialog's statья panel (dev_plan.md §6.4 line 256 — "слева дерево статей по
 * ветке типа") instead of the flat breadcrumb-label [io.qt.widgets.QComboBox] this field used
 * before. Expand/collapse of a branch is [QTreeView]'s own built-in behavior — no custom work
 * needed for that part; this class only adds single-selection semantics on top (one статья per
 * operation) and a way to clear the pick, since the field is optional.
 */
class BudgetItemPicker(private val controller: AppController) : QWidget() {
    private val treeModel = BudgetTreeModel()
    private val treeView = QTreeView()
    private val clearButton = QPushButton(Strings.BUDGET_ITEM_CLEAR)
    private var typeRootId: Long? = null

    var selectedId: Long?
        get() {
            val index = treeView.currentIndex()
            return if (index.isValid) treeModel.itemAt(index)?.id else null
        }
        set(value) {
            if (value == null) {
                treeView.clearSelection()
                treeView.setCurrentIndex(QModelIndex())
                return
            }
            val path = treeModel.indexPath(value)
            if (path.isEmpty()) return
            for (index in path.dropLast(1)) treeView.expand(index)
            treeView.setCurrentIndex(path.last())
            treeView.scrollTo(path.last())
        }

    init {
        val layout = QVBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)

        treeView.setModel(treeModel)
        treeView.setHeaderHidden(true)
        treeView.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        treeView.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        layout.addWidget(treeView, 1)

        clearButton.clicked.connect(QMetaObject.Slot1 { selectedId = null })
        layout.addWidget(clearButton)

        treeModel.setTree(controller.dictionary.state.value.budgetTree, typeRootId)
    }

    /**
     * Restricts the tree to [rootId]'s own subtree — the current operation type's branch
     * (`TypeOper.GroupBudget_ID`, [kalita.core.OperationType.budgetRootId]) — or shows the whole
     * tree when [rootId] is `null` (no type chosen yet). Resets the current pick when it falls
     * outside the new branch, same as the original X-Cash dialog switching «Приход»/«Расход».
     */
    fun setTypeRoot(rootId: Long?) {
        if (rootId == typeRootId) return
        val previous = selectedId
        typeRootId = rootId
        treeModel.setTree(controller.dictionary.state.value.budgetTree, rootId)
        if (previous != null) selectedId = previous
    }
}
