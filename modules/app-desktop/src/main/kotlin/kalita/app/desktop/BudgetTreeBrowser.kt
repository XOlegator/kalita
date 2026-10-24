// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QAbstractItemModel
import io.qt.core.QModelIndex
import io.qt.core.QSettings
import io.qt.core.Qt
import io.qt.gui.QAction
import io.qt.gui.QKeySequence
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QInputDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QMessageBox
import io.qt.widgets.QToolBar
import io.qt.widgets.QTreeView
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.BudgetItem
import kalita.core.BudgetTree
import kalita.presentation.DictKind
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult

/**
 * Browser for the budget item tree (dev_plan.md §5.4, FR-11): tree view with add, add sublevel,
 * edit, delete/restore, move up/down, cut/paste operations.
 */
class BudgetTreeBrowser(
    private val controller: AppController,
    parent: QWidget? = null,
) : QWidget(parent) {

    private val treeModel = BudgetTreeModel()
    private val treeView = QTreeView()
    private val searchField = QLineEdit()
    private val showDeletedAction: QAction
    private val infoLabel = QLabel()
    private var cutItemId: Long? = null

    init {
        val layout = QVBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)

        val toolbar = QToolBar()

        val addAction = toolbar.addAction(Strings.ADD)
        addAction.setShortcut(QKeySequence(QKeySequence.StandardKey.InsertLineSeparator))
        addAction.triggered.connect(this::onAdd)

        val subAction = toolbar.addAction(Strings.ADD_SUBLEVEL)
        subAction.triggered.connect(this::onAddSublevel)

        toolbar.addSeparator()

        val upAction = toolbar.addAction(Strings.MOVE_UP)
        upAction.setShortcut(QKeySequence("Ctrl+Up"))
        upAction.triggered.connect(this::onMoveUp)

        val downAction = toolbar.addAction(Strings.MOVE_DOWN)
        downAction.setShortcut(QKeySequence("Ctrl+Down"))
        downAction.triggered.connect(this::onMoveDown)

        toolbar.addSeparator()

        val editAction = toolbar.addAction(Strings.EDIT)
        editAction.setShortcut(QKeySequence("Ctrl+Return"))
        editAction.triggered.connect(this::onEdit)

        val deleteAction = toolbar.addAction(Strings.DELETE)
        deleteAction.setShortcut(QKeySequence("Ctrl+Delete"))
        deleteAction.triggered.connect(this::onDelete)

        val restoreAction = toolbar.addAction(Strings.RESTORE)
        restoreAction.triggered.connect(this::onRestore)

        toolbar.addSeparator()

        val cutAction = toolbar.addAction(Strings.CUT)
        cutAction.setShortcut(QKeySequence("Ctrl+X"))
        cutAction.triggered.connect(this::onCut)

        val pasteAction = toolbar.addAction(Strings.PASTE)
        pasteAction.setShortcut(QKeySequence("Ctrl+V"))
        pasteAction.triggered.connect(this::onPaste)

        val pasteRootAction = toolbar.addAction(Strings.PASTE_TO_ROOT)
        pasteRootAction.setShortcut(QKeySequence("Ctrl+Alt+V"))
        pasteRootAction.triggered.connect(this::onPasteToRoot)

        toolbar.addSeparator()

        showDeletedAction = toolbar.addAction(Strings.SHOW_DELETED)
        showDeletedAction.setCheckable(true)
        showDeletedAction.setShortcut(QKeySequence("Ctrl+Shift+D"))
        showDeletedAction.toggled.connect(this::onShowDeletedToggled)

        layout.addWidget(toolbar)

        searchField.setPlaceholderText(Strings.SEARCH)
        searchField.setClearButtonEnabled(true)
        searchField.textChanged.connect(this::onSearchChanged)
        layout.addWidget(searchField)

        treeView.setModel(treeModel)
        treeView.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        treeView.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        treeView.setAlternatingRowColors(true)
        treeView.header().setStretchLastSection(true)
        treeView.doubleClicked.connect(this::onDoubleClicked)
        treeView.selectionModel().currentChanged.connect(this::onSelectionChanged)
        layout.addWidget(treeView, 1)

        infoLabel.setWordWrap(true)
        layout.addWidget(infoLabel)

        treeView.header().restoreFrom(QSettings(), "dictionary/BudgetItems/header")

        refreshData()
    }

    /** Column order/width persistence (FR-42), called from [MainWindow.closeEvent]. */
    fun persistHeaderState() {
        treeView.header().persistTo(QSettings(), "dictionary/BudgetItems/header")
    }

    fun refreshData() {
        val tree = controller.dictionary.state.value.budgetTree
        treeModel.setTree(tree)
        treeView.expandAll()
        updateInfo()
    }

    private fun selectedItem(): BudgetItem? {
        val index = treeView.currentIndex()
        return if (index.isValid) treeModel.itemAt(index) else null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAdd(checked: Boolean = false) {
        val selected = selectedItem()
        val parentId = selected?.parentId
        val name = QInputDialog.getText(this, Strings.ADD, "${Strings.NAME}:")
        if (name.isNullOrBlank()) return
        handleResult(controller.addBudgetItem(parentId, name.trim()))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAddSublevel(checked: Boolean = false) {
        val selected = selectedItem() ?: return
        val name = QInputDialog.getText(this, Strings.ADD_SUBLEVEL, "${Strings.NAME}:")
        if (name.isNullOrBlank()) return
        handleResult(controller.addBudgetItem(selected.id, name.trim()))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onEdit(checked: Boolean = false) {
        val item = selectedItem() ?: return
        val name = QInputDialog.getText(
            this, Strings.EDIT, "${Strings.NAME}:",
            QLineEdit.EchoMode.Normal, item.name ?: "",
        )
        if (name.isNullOrBlank()) return
        handleResult(controller.updateBudgetItem(item.id, name.trim()))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDelete(checked: Boolean = false) {
        val item = selectedItem() ?: return
        if (item.deleted) return
        val refCount = controller.dictionary.operationReferenceCount(DictKind.BudgetItem, item.id)
        val message = if (refCount > 0) {
            Strings.confirmDeleteWithRefs(item.name ?: "?", refCount)
        } else {
            Strings.confirmDelete(item.name ?: "?")
        }
        val answer = QMessageBox.question(this, Strings.CONFIRM_DELETE_TITLE, message)
        if (answer != QMessageBox.StandardButton.Yes) return
        handleResult(controller.softDeleteBudgetItem(item.id))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRestore(checked: Boolean = false) {
        val item = selectedItem() ?: return
        if (!item.deleted) return
        handleResult(controller.restoreBudgetItem(item.id))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onMoveUp(checked: Boolean = false) {
        val item = selectedItem() ?: return
        handleResult(controller.moveBudgetItemUp(item.id))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onMoveDown(checked: Boolean = false) {
        val item = selectedItem() ?: return
        handleResult(controller.moveBudgetItemDown(item.id))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCut(checked: Boolean = false) {
        val item = selectedItem() ?: return
        cutItemId = item.id
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onPaste(checked: Boolean = false) {
        val srcId = cutItemId ?: return
        val target = selectedItem() ?: return
        cutItemId = null
        handleResult(controller.moveBudgetItem(srcId, target.id))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onPasteToRoot(checked: Boolean = false) {
        val srcId = cutItemId ?: return
        cutItemId = null
        handleResult(controller.moveBudgetItem(srcId, null))
    }

    private fun onShowDeletedToggled(show: Boolean) {
        controller.dictionary.setShowDeleted(show)
        refreshData()
    }

    private fun onSearchChanged(text: String) {
        controller.dictionary.setSearchQuery(text)
        refreshData()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDoubleClicked(index: QModelIndex) {
        onEdit()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onSelectionChanged(current: QModelIndex, previous: QModelIndex) {
        updateInfo()
    }

    private fun updateInfo() {
        val item = selectedItem()
        if (item == null) {
            infoLabel.setText("")
            return
        }
        val tree = controller.dictionary.state.value.budgetTree
        val path = tree.path(item.id).mapNotNull { it.name }.joinToString(" → ")
        val deleted = if (item.deleted) " [удалён]" else ""
        infoLabel.setText("$path$deleted (id: ${item.id})")
    }

    private fun handleResult(result: SubmitResult) {
        when (result) {
            is SubmitResult.Applied -> refreshData()
            is SubmitResult.Conflicts -> QMessageBox.warning(this, "Конфликт", "Данные изменены извне.")
            is SubmitResult.Failed -> QMessageBox.critical(this, "Ошибка", result.reason)
        }
    }
}

/**
 * Tree model backed by [BudgetTree]. Each node is a [BudgetItem]; the tree is rebuilt on
 * [setTree]. Internal pointers store the parent item's id (0 for roots).
 */
private class BudgetTreeModel : QAbstractItemModel() {
    private var tree = BudgetTree(emptyList())
    private var preorder: List<BudgetItem> = emptyList()

    fun setTree(newTree: BudgetTree) {
        beginResetModel()
        tree = newTree
        preorder = newTree.preorder()
        endResetModel()
    }

    fun itemAt(index: QModelIndex): BudgetItem? {
        if (!index.isValid) return null
        val id = index.internalId()
        val parentId = if (id == 0L) null else id
        val siblings = if (parentId == null) tree.roots else tree.children(parentId)
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
        if (parentId == 0L) return QModelIndex()
        val parentItem = tree.item(parentId) ?: return QModelIndex()
        val grandParentId = parentItem.parentId?.takeIf { tree.item(it) != null }
        val siblings = if (grandParentId == null) tree.roots else tree.children(grandParentId)
        val row = siblings.indexOfFirst { it.id == parentId }
        if (row < 0) return QModelIndex()
        return createIndex(row, 0, grandParentId ?: 0L)
    }

    override fun rowCount(parent: QModelIndex?): Int {
        if (parent == null || !parent.isValid) return tree.roots.size
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
}
