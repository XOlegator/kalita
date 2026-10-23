// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QAbstractTableModel
import io.qt.core.QDate
import io.qt.core.QModelIndex
import io.qt.core.Qt
import io.qt.gui.QKeySequence
import io.qt.widgets.QComboBox
import io.qt.widgets.QDateEdit
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QDoubleSpinBox
import io.qt.widgets.QFormLayout
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QMessageBox
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QTableView
import io.qt.widgets.QToolBar
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.Currency
import kalita.core.Rate
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import java.time.LocalDate

/**
 * Browser for exchange rates (dev_plan.md §5.5, FR-12): table with add, edit, delete (hard),
 * filter by currency and date range.
 */
class RateBrowser(
    private val controller: AppController,
    parent: QWidget? = null,
) : QWidget(parent) {

    private val tableModel = RateTableModel()
    private val tableView = QTableView()
    private val currencyCombo = QComboBox()
    private val fromDate = QDateEdit()
    private val toDate = QDateEdit()
    private var currencies: List<Currency> = emptyList()

    init {
        val layout = QVBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)

        val toolbar = QToolBar()

        val addAction = toolbar.addAction(Strings.ADD)
        addAction.triggered.connect(this::onAdd)

        val editAction = toolbar.addAction(Strings.EDIT)
        editAction.setShortcut(QKeySequence("Ctrl+Return"))
        editAction.triggered.connect(this::onEdit)

        val deleteAction = toolbar.addAction(Strings.DELETE)
        deleteAction.setShortcut(QKeySequence("Ctrl+Delete"))
        deleteAction.triggered.connect(this::onDelete)

        layout.addWidget(toolbar)

        val filterRow = QHBoxLayout()
        filterRow.addWidget(QLabel("${Strings.CURRENCY}:"))
        currencyCombo.addItem(Strings.ALL_CURRENCIES)
        currencyCombo.currentIndexChanged.connect(this::onCurrencyChanged)
        filterRow.addWidget(currencyCombo)

        filterRow.addWidget(QLabel("${Strings.FROM_DATE}:"))
        fromDate.setCalendarPopup(true)
        fromDate.setSpecialValueText(" ")
        fromDate.setDate(QDate(2000, 1, 1))
        fromDate.dateChanged.connect(this::onFromDateChanged)
        filterRow.addWidget(fromDate)

        filterRow.addWidget(QLabel("${Strings.TO_DATE}:"))
        toDate.setCalendarPopup(true)
        toDate.setSpecialValueText(" ")
        toDate.setDate(QDate(2099, 12, 31))
        toDate.dateChanged.connect(this::onToDateChanged)
        filterRow.addWidget(toDate)

        filterRow.addStretch()
        layout.addLayout(filterRow)

        tableView.setModel(tableModel)
        tableView.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        tableView.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        tableView.setAlternatingRowColors(true)
        tableView.horizontalHeader().setStretchLastSection(true)
        tableView.verticalHeader().setVisible(false)
        tableView.doubleClicked.connect(this::onDoubleClicked)
        layout.addWidget(tableView, 1)

        refreshData()
    }

    fun refreshData() {
        val dictState = controller.dictionary.state.value
        currencies = dictState.currencies.filter { !it.deleted }
        rebuildCurrencyCombo()
        val rateState = controller.rate.state.value
        tableModel.setData(rateState.rates, currencies)
    }

    private fun rebuildCurrencyCombo() {
        val current = currencyCombo.currentIndex()
        currencyCombo.blockSignals(true)
        currencyCombo.clear()
        currencyCombo.addItem(Strings.ALL_CURRENCIES)
        for (c in currencies) {
            currencyCombo.addItem(c.name ?: "id:${c.id}")
        }
        if (current in 0 until currencyCombo.count()) {
            currencyCombo.setCurrentIndex(current)
        }
        currencyCombo.blockSignals(false)
    }

    private fun selectedRate(): Rate? {
        val row = tableView.currentIndex().row()
        return if (row >= 0) tableModel.rateAt(row) else null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAdd(checked: Boolean = false) {
        val dialog = RateEditDialog(this, currencies, null, null, null)
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            val currencyId = dialog.selectedCurrencyId() ?: return
            val date = dialog.selectedDate() ?: return
            val value = dialog.selectedValue()
            if (value <= 0.0) return
            handleResult(controller.addRate(currencyId, date, value))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onEdit(checked: Boolean = false) {
        val rate = selectedRate() ?: return
        val dialog = RateEditDialog(this, currencies, rate.currencyId, rate.date, rate.value)
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            val currencyId = dialog.selectedCurrencyId() ?: return
            val date = dialog.selectedDate() ?: return
            val value = dialog.selectedValue()
            if (value <= 0.0) return
            handleResult(controller.updateRate(rate.id, currencyId, date, value))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDelete(checked: Boolean = false) {
        val rate = selectedRate() ?: return
        val currencyName = currencies.firstOrNull { it.id == rate.currencyId }?.name ?: "?"
        val dateStr = rate.date?.let { Formatter.date(it) } ?: "?"
        val answer = QMessageBox.question(
            this, Strings.CONFIRM_DELETE_TITLE,
            Strings.confirmDeleteRate(currencyName, dateStr),
        )
        if (answer != QMessageBox.StandardButton.Yes) return
        handleResult(controller.hardDeleteRate(rate.id))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDoubleClicked(index: QModelIndex) {
        onEdit()
    }

    private fun onCurrencyChanged(index: Int) {
        val currencyId = if (index <= 0) null else currencies.getOrNull(index - 1)?.id
        controller.rate.setFilterCurrency(currencyId)
        tableModel.setData(controller.rate.state.value.rates, currencies)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onFromDateChanged(date: QDate) {
        controller.rate.setFilterFrom(LocalDate.of(date.year(), date.month(), date.day()))
        tableModel.setData(controller.rate.state.value.rates, currencies)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onToDateChanged(date: QDate) {
        controller.rate.setFilterTo(LocalDate.of(date.year(), date.month(), date.day()))
        tableModel.setData(controller.rate.state.value.rates, currencies)
    }

    private fun handleResult(result: SubmitResult) {
        when (result) {
            is SubmitResult.Applied -> refreshData()
            is SubmitResult.Conflicts -> QMessageBox.warning(this, "Конфликт", "Данные изменены извне.")
            is SubmitResult.Failed -> QMessageBox.critical(this, "Ошибка", result.reason)
        }
    }
}

/** Table model for exchange rates: Date, Currency, Rate. */
private class RateTableModel : QAbstractTableModel() {
    private var rates: List<Rate> = emptyList()
    private var currencyNames: Map<Long, String> = emptyMap()

    fun setData(data: List<Rate>, currencies: List<Currency>) {
        beginResetModel()
        rates = data
        currencyNames = currencies.associate { it.id to (it.name ?: "id:${it.id}") }
        endResetModel()
    }

    fun rateAt(row: Int): Rate? = rates.getOrNull(row)

    override fun rowCount(parent: QModelIndex?): Int = rates.size

    override fun columnCount(parent: QModelIndex?): Int = 3

    override fun data(index: QModelIndex, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole) return null
        val rate = rates.getOrNull(index.row()) ?: return null
        return when (index.column()) {
            0 -> rate.date?.let { Formatter.date(it) } ?: ""
            1 -> rate.currencyId?.let { currencyNames[it] } ?: ""
            2 -> rate.value?.let { java.math.BigDecimal.valueOf(it) }?.let { Formatter.rate(it) } ?: ""
            else -> null
        }
    }

    override fun headerData(section: Int, orientation: Qt.Orientation, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole || orientation != Qt.Orientation.Horizontal) return null
        return when (section) {
            0 -> Strings.DATE
            1 -> Strings.CURRENCY
            2 -> Strings.RATE
            else -> null
        }
    }
}

/** Dialog for adding/editing a rate. */
class RateEditDialog(
    parent: QWidget?,
    private val currencies: List<Currency>,
    currencyId: Long?,
    date: LocalDate?,
    value: Double?,
) : QDialog(parent) {

    private val currencyCombo = QComboBox()
    private val dateEdit = QDateEdit()
    private val valueSpinner = QDoubleSpinBox()

    init {
        setWindowTitle(if (value == null) Strings.ADD else Strings.EDIT)
        val layout = QFormLayout(this)

        for (c in currencies) {
            currencyCombo.addItem(c.name ?: "id:${c.id}")
        }
        val idx = currencies.indexOfFirst { it.id == currencyId }
        if (idx >= 0) currencyCombo.setCurrentIndex(idx)
        layout.addRow(Strings.CURRENCY, currencyCombo)

        dateEdit.setCalendarPopup(true)
        val d = date ?: LocalDate.now()
        dateEdit.setDate(QDate(d.year, d.monthValue, d.dayOfMonth))
        layout.addRow(Strings.DATE, dateEdit)

        valueSpinner.setDecimals(6)
        valueSpinner.setMaximum(999999999.0)
        valueSpinner.setValue(value ?: 1.0)
        layout.addRow(Strings.RATE, valueSpinner)

        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(
                QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel,
            ),
        )
        buttons.accepted.connect(this::accept)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    fun selectedCurrencyId(): Long? {
        val idx = currencyCombo.currentIndex()
        return currencies.getOrNull(idx)?.id
    }

    fun selectedDate(): LocalDate? {
        val d = dateEdit.date()
        return LocalDate.of(d.year(), d.month(), d.day())
    }

    fun selectedValue(): Double = valueSpinner.value()
}
