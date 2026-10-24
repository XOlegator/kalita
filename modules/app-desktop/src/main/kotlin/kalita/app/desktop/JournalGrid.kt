// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QAbstractTableModel
import io.qt.core.QMetaObject
import io.qt.core.QModelIndex
import io.qt.core.QPoint
import io.qt.core.QSettings
import io.qt.core.Qt
import io.qt.gui.QAction
import io.qt.gui.QBrush
import io.qt.gui.QColor
import io.qt.gui.QGuiApplication
import io.qt.gui.QKeySequence
import io.qt.gui.QResizeEvent
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QCheckBox
import io.qt.widgets.QComboBox
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QInputDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QListWidget
import io.qt.widgets.QMenu
import io.qt.widgets.QMessageBox
import io.qt.widgets.QPushButton
import io.qt.widgets.QTableView
import io.qt.widgets.QToolBar
import io.qt.widgets.QToolButton
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.Aggregate
import kalita.core.JournalColumn
import kalita.core.JournalFilterJson
import kalita.core.JournalRow
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.core.SortKey
import kalita.presentation.Formatter
import kalita.presentation.JournalRowFormatter
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import java.math.BigDecimal

/**
 * The income/expense journal grid (dev_plan.md §6.2, FR-20): table with all columns, toolbar
 * with reduction currency, add/edit/copy/delete/restore, footer with aggregates, and status bar info.
 */
class JournalGrid(
    private val controller: AppController,
    private val settings: AppSettings,
    parent: QWidget? = null,
) : QWidget(parent) {

    private val tableModel = JournalTableModel()
    private val tableView = QTableView()

    // Frozen pane for pinned columns (§6.2 "закрепление... колонок", FR-41): a second view over
    // the same model, showing only the pinned columns while the main view hides them — each
    // column renders in exactly one of the two views, never both.
    private val frozenView = QTableView()
    private val pinnedColumns = linkedSetOf<JournalColumn>()
    private val hiddenColumns = mutableSetOf<JournalColumn>()

    private val currencyCombo = QComboBox()
    private val aggCombo = QComboBox()
    private val footerLabel = QLabel()
    private val rowCountLabel = QLabel()
    private val textFilterField = QLineEdit()
    private val viewButton = QToolButton()
    private val viewMenu = QMenu(this)
    private var showDeletedAction: QAction? = null
    private var lastSearchTerm: String? = null
    private var sortFilterRestored = false

    /** Pushed the current row (or `null`) on every selection change — feeds [InformationPanel] (FR-40). */
    var onSelectionChanged: ((JournalRow?) -> Unit)? = null

    private val settingsGroup = "journal/incomeExpense"

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

        toolbar.addSeparator()

        val findAction = toolbar.addAction(Strings.FIND)
        findAction?.setShortcut(QKeySequence("Ctrl+F"))
        findAction?.triggered?.connect(this::onFind)

        val findNextAction = toolbar.addAction(Strings.FIND_NEXT)
        findNextAction?.setShortcut(QKeySequence("F3"))
        findNextAction?.triggered?.connect(this::onFindNext)

        textFilterField.setPlaceholderText(Strings.SUBSTRING_FILTER_PLACEHOLDER)
        textFilterField.setClearButtonEnabled(true)
        textFilterField.textChanged.connect(this::onTextFilterChanged)
        toolbar.addWidget(textFilterField)

        toolbar.addSeparator()

        val columnVisibilityAction = toolbar.addAction(Strings.COLUMN_VISIBILITY)
        columnVisibilityAction?.setShortcut(QKeySequence("Ctrl+D"))
        columnVisibilityAction?.triggered?.connect(this::showColumnVisibilityDialog)

        // «Вид» (§6.2 toolbar, FR-43): named layouts, Alt+S/Alt+L.
        viewButton.setText(Strings.VIEW)
        viewButton.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        viewButton.setMenu(viewMenu)
        refreshViewMenu()
        toolbar.addWidget(viewButton)

        layout.addWidget(toolbar)

        tableView.setModel(tableModel)
        tableView.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        tableView.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        tableView.setAlternatingRowColors(true)
        tableView.horizontalHeader()?.setStretchLastSection(true)
        tableView.horizontalHeader()?.sectionClicked?.connect(this::onHeaderClicked)
        tableView.horizontalHeader()?.setSectionsMovable(true)
        tableView.horizontalHeader()?.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        tableView.horizontalHeader()?.customContextMenuRequested?.connect(this::onHeaderContextMenu)
        tableView.verticalHeader()?.setVisible(false)
        tableView.setSortingEnabled(false)
        tableView.doubleClicked.connect(this::onDoubleClicked)
        tableView.selectionModel()?.currentChanged?.connect(this::onCurrentRowChanged)
        layout.addWidget(tableView, 1)

        setupFrozenView()
        restoreHeaderAndPins()

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
        restoreSortFilterOnce()
        val dictState = controller.dictionary.state.value
        rebuildCurrencyCombo(dictState.currencies)
        val journalState = controller.journal.state.value
        tableModel.setRows(journalState.rows, controller)
        updateFooter()
        // A model reset drops the selection without emitting currentChanged — resolve explicitly.
        onSelectionChanged?.invoke(null)
    }

    override fun resizeEvent(event: QResizeEvent) {
        super.resizeEvent(event)
        updateFrozenGeometry()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCurrentRowChanged(current: QModelIndex, previous: QModelIndex) {
        onSelectionChanged?.invoke(if (current.isValid) tableModel.rowAt(current.row()) else null)
    }

    // --- Pinned columns / frozen pane (FR-41) ---

    private fun setupFrozenView() {
        frozenView.setParent(tableView)
        frozenView.setModel(tableModel)
        frozenView.setSelectionMode(QAbstractItemView.SelectionMode.NoSelection)
        frozenView.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        frozenView.setAlternatingRowColors(true)
        frozenView.verticalHeader()?.setVisible(false)
        frozenView.horizontalHeader()?.setStretchLastSection(false)
        frozenView.horizontalHeader()?.setSectionsMovable(false)
        frozenView.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        frozenView.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        frozenView.setFrameShape(io.qt.widgets.QFrame.Shape.NoFrame)
        frozenView.setVisible(false)
        // Bidirectional, guarded so the two views don't bounce a wheel/drag scroll back and forth.
        tableView.verticalScrollBar()?.valueChanged?.connect(this::onMainVerticalScroll)
        frozenView.verticalScrollBar()?.valueChanged?.connect(this::onFrozenVerticalScroll)
    }

    private fun onMainVerticalScroll(value: Int) {
        if (frozenView.verticalScrollBar()?.value() != value) frozenView.verticalScrollBar()?.setValue(value)
    }

    private fun onFrozenVerticalScroll(value: Int) {
        if (tableView.verticalScrollBar()?.value() != value) tableView.verticalScrollBar()?.setValue(value)
    }

    internal fun togglePin(column: JournalColumn) {
        if (!pinnedColumns.remove(column)) pinnedColumns.add(column)
        refreshColumnVisibility()
    }

    internal fun hideColumn(column: JournalColumn) {
        hiddenColumns.add(column)
        refreshColumnVisibility()
    }

    internal fun showAllColumns() {
        hiddenColumns.clear()
        refreshColumnVisibility()
    }

    /** Recomputes which columns each view hides, from [hiddenColumns] and [pinnedColumns]. */
    private fun refreshColumnVisibility() {
        for (column in COLUMNS) {
            val idx = COLUMNS.indexOf(column)
            val pinned = column in pinnedColumns
            tableView.horizontalHeader()?.setSectionHidden(idx, pinned || column in hiddenColumns)
            frozenView.horizontalHeader()?.setSectionHidden(idx, !pinned || column in hiddenColumns)
            if (pinned) frozenView.setColumnWidth(idx, tableView.columnWidth(idx))
        }
        updateFrozenGeometry()
    }

    private fun updateFrozenGeometry() {
        if (pinnedColumns.isEmpty()) {
            frozenView.setVisible(false)
            return
        }
        val width = pinnedColumns.sumOf { tableView.columnWidth(COLUMNS.indexOf(it)) }
        val scrollBarHeight = tableView.horizontalScrollBar()?.let { if (it.isVisible) it.height() else 0 } ?: 0
        frozenView.setGeometry(
            tableView.frameWidth(),
            tableView.frameWidth(),
            width,
            tableView.height() - tableView.frameWidth() * 2 - scrollBarHeight,
        )
        frozenView.setVisible(true)
        frozenView.raise()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onHeaderContextMenu(pos: QPoint) {
        val header = tableView.horizontalHeader() ?: return
        val logicalIndex = header.logicalIndexAt(pos)
        val column = COLUMNS.getOrNull(logicalIndex)
        val menu = QMenu(this)
        if (column != null) {
            menu.addAction(Strings.HIDE_COLUMN)?.triggered?.connect(QMetaObject.Slot0 { hideColumn(column) })
            val pinLabel = if (column in pinnedColumns) Strings.UNPIN_COLUMN else Strings.PIN_COLUMN
            menu.addAction(pinLabel)?.triggered?.connect(QMetaObject.Slot0 { togglePin(column) })
            menu.addSeparator()
        }
        menu.addAction(Strings.SHOW_ALL_COLUMNS)?.triggered?.connect(QMetaObject.Slot0 { showAllColumns() })
        menu.exec(header.mapToGlobal(pos))
    }

    @Suppress("UNUSED_PARAMETER")
    fun showColumnVisibilityDialog(checked: Boolean = false) {
        val dialog = ColumnVisibilityDialog(this, hiddenColumns)
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            hiddenColumns.clear()
            hiddenColumns.addAll(dialog.hiddenColumns())
            refreshColumnVisibility()
        }
    }

    // --- Named views (§6.2 "«Вид»", FR-43) ---

    fun saveNamedView() {
        val name = QInputDialog.getText(this, Strings.SAVE_VIEW, Strings.SAVE_VIEW_PROMPT)
        if (name.isNullOrBlank()) return
        settings.addOrReplaceSavedView(captureView(name.trim()))
        refreshViewMenu()
    }

    fun showLoadViewDialog() {
        val dialog = LoadViewDialog(this, settings)
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            dialog.selectedView()?.let(this::applyView)
        }
        refreshViewMenu()
    }

    internal fun captureView(name: String): SavedView {
        val state = controller.journal.state.value
        val headerBytes = tableView.horizontalHeader()?.saveState()?.toArray() ?: ByteArray(0)
        return SavedView(
            name,
            headerBytes.toHeaderHex(),
            pinnedColumns.map { it.name },
            encodeSortCompact(state.sort),
            state.reductionCurrencyId,
            JournalFilterJson.encodeToString(controller.filter.state.value.filter),
        )
    }

    internal fun applyView(view: SavedView) {
        tableView.horizontalHeader()?.restoreState(view.headerStateHex.fromHeaderHex())
        pinnedColumns.clear()
        view.pinnedColumns.mapNotNullTo(pinnedColumns) { name -> runCatching { JournalColumn.valueOf(name) }.getOrNull() }
        refreshColumnVisibility()
        controller.journal.setSort(decodeSortCompact(view.sortEncoded))
        if (view.reductionCurrencyId != null) controller.journal.setReductionCurrency(view.reductionCurrencyId)
        runCatching { JournalFilterJson.decodeFromString(view.filterJson) }.getOrNull()?.let(controller::applyFilter)
        refreshData()
    }

    private fun refreshViewMenu() {
        viewMenu.clear()
        val saveAction = viewMenu.addAction(Strings.SAVE_VIEW)
        saveAction?.setShortcut(QKeySequence("Alt+S"))
        saveAction?.triggered?.connect(QMetaObject.Slot0 { saveNamedView() })
        val loadAction = viewMenu.addAction(Strings.LOAD_VIEW)
        loadAction?.setShortcut(QKeySequence("Alt+L"))
        loadAction?.triggered?.connect(QMetaObject.Slot0 { showLoadViewDialog() })
        val views = settings.savedViews
        if (views.isNotEmpty()) {
            viewMenu.addSeparator()
            for (v in views) {
                viewMenu.addAction(v.name)?.triggered?.connect(QMetaObject.Slot0 { applyView(v) })
            }
        }
    }

    // --- Per-screen layout persistence (FR-42) ---

    /** Called from [MainWindow.closeEvent], alongside the window's own geometry/dock state. */
    fun persistState() {
        val qs = QSettings()
        tableView.horizontalHeader()?.persistTo(qs, "$settingsGroup/header")
        qs.setValue("$settingsGroup/pinned", pinnedColumns.joinToString(",") { it.name })
        qs.setValue("$settingsGroup/hidden", hiddenColumns.joinToString(",") { it.name })
        val state = controller.journal.state.value
        qs.setValue("$settingsGroup/sort", encodeSortCompact(state.sort))
        qs.setValue("$settingsGroup/reductionCurrencyId", state.reductionCurrencyId?.toString() ?: "")
        qs.setValue("$settingsGroup/filter", JournalFilterJson.encodeToString(controller.filter.state.value.filter))
    }

    /** Header order/width/visibility and pins don't need an open database — restore right away. */
    private fun restoreHeaderAndPins() {
        val qs = QSettings()
        tableView.horizontalHeader()?.restoreFrom(qs, "$settingsGroup/header")
        (qs.value("$settingsGroup/pinned") as? String)?.split(",")?.filter { it.isNotEmpty() }?.forEach { name ->
            runCatching { JournalColumn.valueOf(name) }.getOrNull()?.let(pinnedColumns::add)
        }
        (qs.value("$settingsGroup/hidden") as? String)?.split(",")?.filter { it.isNotEmpty() }?.forEach { name ->
            runCatching { JournalColumn.valueOf(name) }.getOrNull()?.let(hiddenColumns::add)
        }
        refreshColumnVisibility()
    }

    /** Sort/reduction currency/filter need the dataset to mean anything — restore once it is loaded. */
    private fun restoreSortFilterOnce() {
        if (sortFilterRestored) return
        sortFilterRestored = true
        val qs = QSettings()
        (qs.value("$settingsGroup/sort") as? String)?.let { controller.journal.setSort(decodeSortCompact(it)) }
        (qs.value("$settingsGroup/reductionCurrencyId") as? String)?.toLongOrNull()?.let {
            controller.journal.setReductionCurrency(it)
        }
        (qs.value("$settingsGroup/filter") as? String)?.let { json ->
            runCatching { JournalFilterJson.decodeFromString(json) }.getOrNull()?.let(controller::applyFilter)
        }
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

    private fun onHeaderClicked(section: Int) {
        val column = COLUMNS.getOrNull(section) ?: return
        val shift = QGuiApplication.keyboardModifiers().testFlag(Qt.KeyboardModifier.ShiftModifier)
        val current = controller.journal.state.value.sort
        val existingIndex = current.indexOfFirst { it.column == column }
        val newSort = when {
            shift && existingIndex >= 0 -> current.toMutableList().also {
                it[existingIndex] = it[existingIndex].copy(descending = !it[existingIndex].descending)
            }
            shift -> current + SortKey(column)
            existingIndex == 0 && current.size == 1 -> listOf(SortKey(column, descending = !current[0].descending))
            else -> listOf(SortKey(column))
        }
        controller.journal.setSort(newSort)
        tableModel.setRows(controller.journal.state.value.rows, controller)
    }

    private fun onTextFilterChanged(text: String) {
        controller.journal.setTextFilter(text)
        tableModel.setRows(controller.journal.state.value.rows, controller)
        updateFooter()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onFind(checked: Boolean = false) {
        val term = QInputDialog.getText(this, Strings.FIND, Strings.FIND_PROMPT, QLineEdit.EchoMode.Normal, lastSearchTerm ?: "")
        if (term.isNullOrBlank()) return
        lastSearchTerm = term
        search(term, tableView.currentIndex().row() + 1)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onFindNext(checked: Boolean = false) {
        val term = lastSearchTerm ?: return
        search(term, tableView.currentIndex().row() + 1)
    }

    private fun search(term: String, fromRow: Int) {
        val rows = controller.journal.state.value.rows
        if (rows.isEmpty()) return
        val dataset = (controller.state.value as? AppState.Ready)?.dataset ?: return
        val n = rows.size
        for (offset in 0 until n) {
            val row = ((fromRow + offset) % n + n) % n
            if (JournalRowFormatter.allText(dataset, rows[row]).contains(term, ignoreCase = true)) {
                tableView.selectRow(row)
                tableView.scrollTo(tableModel.index(row, 0))
                return
            }
        }
        QMessageBox.information(this, Strings.FIND, Strings.FIND_NOT_FOUND)
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
    private var sort: List<SortKey> = emptyList()

    fun setRows(data: List<JournalRow>, controller: AppController) {
        beginResetModel()
        rows = data
        dataset = (controller.state.value as? AppState.Ready)?.dataset ?: kalita.core.Dataset.EMPTY
        sort = controller.journal.state.value.sort
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
        val column = COLUMNS.getOrNull(section) ?: return null
        val base = Strings.columnName(column)
        val level = sort.indexOfFirst { it.column == column }
        if (level < 0) return base
        val arrow = if (sort[level].descending) "▼" else "▲"
        return "$base $arrow${level + 1}"
    }

    private fun cellText(row: JournalRow, col: JournalColumn): String = JournalRowFormatter.cellText(dataset, row, col)
}

/** "Видимость столбцов" dialog (`Ctrl+D`, §6.2/FR-41): a checkbox per [JournalColumn]. */
private class ColumnVisibilityDialog(parent: QWidget?, hidden: Set<JournalColumn>) : QDialog(parent) {
    private val checks: Map<JournalColumn, QCheckBox> =
        JournalColumn.entries.associateWith { QCheckBox(Strings.columnName(it)).apply { setChecked(it !in hidden) } }

    init {
        setWindowTitle(Strings.COLUMN_VISIBILITY_TITLE)
        val layout = QVBoxLayout(this)
        for (column in JournalColumn.entries) layout.addWidget(checks.getValue(column))
        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel),
        )
        buttons.accepted.connect(this::accept)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    fun hiddenColumns(): Set<JournalColumn> = JournalColumn.entries.filterNotTo(HashSet()) { checks.getValue(it).isChecked() }
}

/** Dialog listing saved named views (§6.2 "«Вид»", FR-43), with delete — mirrors [LoadFilterDialog]. */
private class LoadViewDialog(parent: QWidget?, private val settings: AppSettings) : QDialog(parent) {
    private val list = QListWidget()
    private var views: List<SavedView> = emptyList()

    init {
        setWindowTitle(Strings.LOAD_VIEW_TITLE)
        val layout = QVBoxLayout(this)
        layout.addWidget(list, 1)
        refresh()

        val deleteButton = QPushButton(Strings.DELETE)
        deleteButton.clicked.connect(this::onDelete)
        layout.addWidget(deleteButton)

        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel),
        )
        buttons.accepted.connect(this::accept)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    private fun refresh() {
        views = settings.savedViews
        list.clear()
        if (views.isEmpty()) {
            list.addItem(Strings.NO_SAVED_VIEWS)
            return
        }
        for (v in views) list.addItem(v.name)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDelete(checked: Boolean = false) {
        val v = views.getOrNull(list.currentRow()) ?: return
        settings.removeSavedView(v.name)
        refresh()
    }

    fun selectedView(): SavedView? = views.getOrNull(list.currentRow())
}
