// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QAbstractTableModel
import io.qt.core.QModelIndex
import io.qt.core.Qt
import io.qt.gui.QAction
import io.qt.gui.QBrush
import io.qt.gui.QColor
import io.qt.gui.QKeySequence
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QComboBox
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QMessageBox
import io.qt.widgets.QTableView
import io.qt.widgets.QToolBar
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.Aggregate
import kalita.core.JournalColumn
import kalita.core.JournalRow
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import java.math.BigDecimal

/**
 * The income/expense journal grid (dev_plan.md §6.2, FR-20): table with all columns, toolbar
 * with reduction currency, add/edit/copy/delete/restore, footer with aggregates, and status bar info.
 */
class JournalGrid(
    private val controller: AppController,
    parent: QWidget? = null,
) : QWidget(parent) {

    private val tableModel = JournalTableModel()
    private val tableView = QTableView()
    private val currencyCombo = QComboBox()
    private val aggCombo = QComboBox()
    private val footerLabel = QLabel()
    private val rowCountLabel = QLabel()
    private var showDeletedAction: QAction? = null

    init {
        val layout = QVBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)

        val toolbar = QToolBar()

        val addAction = toolbar.addAction(Strings.ADD)
        addAction?.setShortcut(QKeySequence("Insert"))
        addAction?.triggered?.connect(this::onAdd)

        val editAction = toolbar.addAction(Strings.EDIT)
        editAction?.setShortcut(QKeySequence("Ctrl+Return"))
        editAction?.triggered?.connect(this::onEdit)

        val copyAction = toolbar.addAction(Strings.COPY)
        copyAction?.triggered?.connect(this::onCopy)

        val deleteAction = toolbar.addAction(Strings.DELETE)
        deleteAction?.setShortcut(QKeySequence("Ctrl+Delete"))
        deleteAction?.triggered?.connect(this::onDelete)

        val restoreAction = toolbar.addAction(Strings.RESTORE)
        restoreAction?.triggered?.connect(this::onRestore)

        toolbar.addSeparator()

        showDeletedAction = toolbar.addAction(Strings.SHOW_DELETED)
        showDeletedAction?.setCheckable(true)
        showDeletedAction?.setShortcut(QKeySequence("Ctrl+Shift+D"))
        showDeletedAction?.toggled?.connect(this::onShowDeletedToggled)

        toolbar.addSeparator()

        toolbar.addWidget(QLabel("${Strings.REDUCTION_CURRENCY}: "))
        currencyCombo.currentIndexChanged.connect(this::onCurrencyChanged)
        toolbar.addWidget(currencyCombo)

        layout.addWidget(toolbar)

        tableView.setModel(tableModel)
        tableView.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        tableView.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        tableView.setAlternatingRowColors(true)
        tableView.horizontalHeader()?.setStretchLastSection(true)
        tableView.verticalHeader()?.setVisible(false)
        tableView.setSortingEnabled(false)
        tableView.doubleClicked.connect(this::onDoubleClicked)
        layout.addWidget(tableView, 1)

        val footerRow = QHBoxLayout()
        footerRow.addWidget(QLabel("${Strings.AGG_SUM}: "))
        aggCombo.addItem(Strings.AGG_SUM)
        aggCombo.addItem(Strings.AGG_MIN)
        aggCombo.addItem(Strings.AGG_MAX)
        aggCombo.addItem(Strings.AGG_AVG)
        aggCombo.addItem(Strings.AGG_COUNT)
        aggCombo.currentIndexChanged.connect(this::onAggChanged)
        footerRow.addWidget(aggCombo)
        footerRow.addWidget(footerLabel, 1)
        footerRow.addWidget(rowCountLabel)
        layout.addLayout(footerRow)
    }

    fun refreshData() {
        val dictState = controller.dictionary.state.value
        rebuildCurrencyCombo(dictState.currencies)
        val journalState = controller.journal.state.value
        tableModel.setRows(journalState.rows, controller)
        updateFooter()
    }

    private fun rebuildCurrencyCombo(currencies: List<kalita.core.Currency>) {
        val current = currencyCombo.currentIndex()
        currencyCombo.blockSignals(true)
        currencyCombo.clear()
        for (c in currencies) {
            currencyCombo.addItem(c.name ?: "id:${c.id}")
        }
        val reductionId = controller.journal.state.value.reductionCurrencyId
        val idx = currencies.indexOfFirst { it.id == reductionId }
        if (idx >= 0) currencyCombo.setCurrentIndex(idx)
        else if (current in 0 until currencyCombo.count()) currencyCombo.setCurrentIndex(current)
        currencyCombo.blockSignals(false)
    }

    private fun updateFooter() {
        val state = controller.journal.state.value
        val amtStr = state.footerAmount?.let { Formatter.amount(it) } ?: "—"
        val convStr = state.footerConvertedAmount?.let { Formatter.amount(it) } ?: "—"
        footerLabel.setText("  ${Strings.AMOUNT}: $amtStr  |  ${Strings.columnName(JournalColumn.ConvertedAmount)}: $convStr")
        rowCountLabel.setText("${Strings.ROW_COUNT_PREFIX}${state.rows.size}")
    }

    private fun selectedRows(): List<JournalRow> {
        val indices = tableView.selectionModel()?.selectedRows() ?: return emptyList()
        return indices.mapNotNull { tableModel.rowAt(it.row()) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAdd(checked: Boolean = false) {
        controller.operationForm.newOperation()
        val dialog = OperationFormDialog(this, controller, Strings.NEW_OPERATION)
        if (dialog.exec() != 0) refreshData()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onEdit(checked: Boolean = false) {
        val row = selectedRows().firstOrNull() ?: return
        val op = row.operation
        val draft = OperationDraft(
            date = op.date, amount = op.amount, currencyId = op.currencyId,
            budgetItemId = op.budgetItemId, typeId = op.typeId, comment = op.comment,
            counterpartyId = op.counterpartyId, accountFrom = op.accountFrom, accountTo = op.accountTo,
        )
        controller.operationForm.editOperation(draft)
        val dialog = OperationFormDialog(this, controller, Strings.EDIT_OPERATION, editId = op.id)
        if (dialog.exec() != 0) refreshData()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCopy(checked: Boolean = false) {
        val row = selectedRows().firstOrNull() ?: return
        val op = row.operation
        val draft = OperationDraft(
            date = op.date, amount = op.amount, currencyId = op.currencyId,
            budgetItemId = op.budgetItemId, typeId = op.typeId, comment = op.comment,
            counterpartyId = op.counterpartyId, accountFrom = op.accountFrom, accountTo = op.accountTo,
        )
        controller.operationForm.editOperation(draft)
        val dialog = OperationFormDialog(this, controller, Strings.COPY_OPERATION)
        if (dialog.exec() != 0) refreshData()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDelete(checked: Boolean = false) {
        val rows = selectedRows()
        if (rows.isEmpty()) return
        val live = rows.filter { !it.operation.deleted }
        if (live.isEmpty()) return
        val answer = QMessageBox.question(
            this, Strings.CONFIRM_DELETE_TITLE,
            Strings.confirmDeleteOperations(live.size),
        )
        if (answer != QMessageBox.StandardButton.Yes) return
        handleResult(controller.softDeleteOperations(live.map { it.operation.id }))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRestore(checked: Boolean = false) {
        val rows = selectedRows()
        val deleted = rows.filter { it.operation.deleted }
        if (deleted.isEmpty()) return
        handleResult(controller.restoreOperations(deleted.map { it.operation.id }))
    }

    private fun onShowDeletedToggled(show: Boolean) {
        controller.journal.setShowDeleted(show)
        tableModel.setRows(controller.journal.state.value.rows, controller)
        updateFooter()
    }

    private fun onCurrencyChanged(index: Int) {
        val currencies = controller.dictionary.state.value.currencies
        val currencyId = currencies.getOrNull(index)?.id ?: return
        controller.journal.setReductionCurrency(currencyId)
        controller.saldo.setReductionCurrency(currencyId)
        tableModel.setRows(controller.journal.state.value.rows, controller)
        updateFooter()
    }

    private fun onAggChanged(index: Int) {
        val agg = when (index) {
            0 -> Aggregate.Sum
            1 -> Aggregate.Min
            2 -> Aggregate.Max
            3 -> Aggregate.Average
            4 -> Aggregate.Count
            else -> Aggregate.Sum
        }
        controller.journal.setAggregateFunction(agg)
        updateFooter()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDoubleClicked(index: QModelIndex) {
        onEdit()
    }

    private fun handleResult(result: SubmitResult) {
        when (result) {
            is SubmitResult.Applied -> refreshData()
            is SubmitResult.Conflicts -> QMessageBox.warning(this, "Конфликт", "Данные изменены извне.")
            is SubmitResult.Failed -> QMessageBox.critical(this, "Ошибка", result.reason)
        }
    }
}

private val COLUMNS = JournalColumn.entries.toTypedArray()
private val TRANSFER_COLOR = QColor(0, 128, 0)

/** Table model for the journal grid — one row per [JournalRow]. */
private class JournalTableModel : QAbstractTableModel() {
    private var rows: List<JournalRow> = emptyList()
    private var dataset: kalita.core.Dataset = kalita.core.Dataset.EMPTY

    fun setRows(data: List<JournalRow>, controller: AppController) {
        beginResetModel()
        rows = data
        dataset = (controller.state.value as? AppState.Ready)?.dataset ?: kalita.core.Dataset.EMPTY
        endResetModel()
    }

    fun rowAt(row: Int): JournalRow? = rows.getOrNull(row)

    override fun rowCount(parent: QModelIndex): Int = rows.size

    override fun columnCount(parent: QModelIndex): Int = COLUMNS.size

    override fun data(index: QModelIndex, role: Int): Any? {
        val row = rows.getOrNull(index.row()) ?: return null
        val col = COLUMNS.getOrNull(index.column()) ?: return null

        if (role == Qt.ItemDataRole.DisplayRole) {
            return cellText(row, col)
        }
        if (role == Qt.ItemDataRole.ForegroundRole) {
            val kind = dataset.operationType(row.operation.typeId)?.kind
            if (kind == OperationKind.Transfer) return QBrush(TRANSFER_COLOR)
            if (row.operation.deleted) return QBrush(QColor(160, 160, 160))
        }
        return null
    }

    override fun headerData(section: Int, orientation: Qt.Orientation, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole || orientation != Qt.Orientation.Horizontal) return null
        return COLUMNS.getOrNull(section)?.let { Strings.columnName(it) }
    }

    private fun cellText(row: JournalRow, col: JournalColumn): String {
        val op = row.operation
        val v = row.values
        return when (col) {
            JournalColumn.Date -> op.date?.let { Formatter.date(it) } ?: ""
            JournalColumn.AccountFrom -> dataset.account(op.accountFrom)?.name ?: ""
            JournalColumn.Amount -> v.signedAmount?.let { Formatter.amount(it) } ?: ""
            JournalColumn.ConvertedAmount -> v.convertedAmount?.let { Formatter.amount(it) } ?: ""
            JournalColumn.OperationType -> dataset.operationType(op.typeId)?.name ?: ""
            JournalColumn.BudgetItem -> dataset.budgetItem(op.budgetItemId)?.name ?: ""
            JournalColumn.Currency -> dataset.currency(op.currencyId)?.name ?: ""
            JournalColumn.Counterparty -> dataset.counterparty(op.counterpartyId)?.name ?: ""
            JournalColumn.Comment -> op.comment ?: ""
            JournalColumn.AccountTo -> dataset.account(op.accountTo)?.name ?: ""
            JournalColumn.Rate -> v.rate?.let { Formatter.rate(it) } ?: ""
        }
    }
}
