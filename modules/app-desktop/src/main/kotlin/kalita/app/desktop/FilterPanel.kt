// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QDate
import io.qt.core.QMetaObject
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.gui.QKeySequence
import io.qt.widgets.QCheckBox
import io.qt.widgets.QComboBox
import io.qt.widgets.QDateEdit
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QDoubleSpinBox
import io.qt.widgets.QFormLayout
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QInputDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QListWidget
import io.qt.widgets.QListWidgetItem
import io.qt.widgets.QMenu
import io.qt.widgets.QPushButton
import io.qt.widgets.QStackedWidget
import io.qt.widgets.QToolButton
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.AmountCondition
import kalita.core.DateAnchor
import kalita.core.DateBound
import kalita.core.DateCondition
import kalita.core.DateInterval
import kalita.core.FilterCondition
import kalita.core.JournalFilter
import kalita.core.JournalFilterJson
import kalita.core.RefCondition
import kalita.core.RefField
import kalita.core.TextCondition
import kalita.core.TextField
import kalita.presentation.Formatter
import kalita.presentation.Strings
import java.math.BigDecimal
import java.time.LocalDate

/** One field of the panel (dev_plan.md §6.3) — bold ones are "справочные" (reference fields). */
private enum class FilterField(val label: String, val bold: Boolean) {
    Date(Strings.DATE, false),
    OperationType(Strings.OPERATION_TYPE, true),
    BudgetItem(Strings.BUDGET_ITEM, true),
    Amount(Strings.AMOUNT, false),
    Currency(Strings.CURRENCY, true),
    Comment(Strings.COMMENT, false),
    Counterparty(Strings.COUNTERPARTY, true),
    AccountFrom(Strings.ACCOUNT_FROM, true),
    AccountTo(Strings.ACCOUNT_TO, true),
    Account(Strings.ACCOUNT, true),
}

private fun fieldOf(condition: FilterCondition): FilterField = when (condition) {
    is DateCondition -> FilterField.Date
    is AmountCondition -> FilterField.Amount
    is TextCondition -> FilterField.Comment
    is RefCondition -> when (condition.field) {
        RefField.OperationType -> FilterField.OperationType
        RefField.BudgetItem -> FilterField.BudgetItem
        RefField.Currency -> FilterField.Currency
        RefField.Counterparty -> FilterField.Counterparty
        RefField.AccountFrom -> FilterField.AccountFrom
        RefField.AccountTo -> FilterField.AccountTo
        RefField.Account -> FilterField.Account
    }
}

/** One field's editor page in the panel's `QStackedWidget`. */
private interface FieldEditor {
    val widget: QWidget
    fun buildCondition(): FilterCondition
    fun loadCondition(condition: FilterCondition)
    fun reset()
}

/** Date field editor: current day/month/quarter/year, one fixed interval, or several (§6.3). */
private class DateFieldEditor : QWidget(), FieldEditor {
    override val widget: QWidget get() = this
    private val modeCombo = QComboBox()
    private val customRow = QWidget()
    private val fromEdit = QDateEdit()
    private val toEdit = QDateEdit()
    private val multiRow = QWidget()
    private val multiList = QListWidget()
    private val multiIntervals = mutableListOf<DateInterval>()
    private val emptyCheck = QCheckBox(Strings.FLAG_EMPTY)
    private val invertedCheck = QCheckBox(Strings.FLAG_INVERTED)

    init {
        val layout = QVBoxLayout(this)
        modeCombo.addItem(Strings.DATE_MODE_TODAY)
        modeCombo.addItem(Strings.DATE_MODE_MONTH)
        modeCombo.addItem(Strings.DATE_MODE_QUARTER)
        modeCombo.addItem(Strings.DATE_MODE_YEAR)
        modeCombo.addItem(Strings.DATE_MODE_INTERVAL)
        modeCombo.addItem(Strings.DATE_MODE_MULTI_INTERVAL)
        modeCombo.setCurrentIndex(1)
        modeCombo.currentIndexChanged.connect(this::onModeChanged)
        layout.addWidget(modeCombo)

        val customLayout = QHBoxLayout(customRow)
        fromEdit.setCalendarPopup(true)
        toEdit.setCalendarPopup(true)
        customLayout.addWidget(QLabel("${Strings.FROM_DATE}:"))
        customLayout.addWidget(fromEdit)
        customLayout.addWidget(QLabel("${Strings.TO_DATE}:"))
        customLayout.addWidget(toEdit)
        layout.addWidget(customRow)

        val multiLayout = QVBoxLayout(multiRow)
        multiLayout.addWidget(multiList)
        val multiButtons = QHBoxLayout()
        val addButton = QPushButton(Strings.ADD_INTERVAL)
        addButton.clicked.connect(this::onAddInterval)
        val removeButton = QPushButton(Strings.DELETE)
        removeButton.clicked.connect(this::onRemoveInterval)
        multiButtons.addWidget(addButton)
        multiButtons.addWidget(removeButton)
        multiLayout.addLayout(multiButtons)
        layout.addWidget(multiRow)

        layout.addWidget(emptyCheck)
        layout.addWidget(invertedCheck)
        layout.addStretch()

        onModeChanged(modeCombo.currentIndex())
    }

    private fun onModeChanged(index: Int) {
        customRow.setVisible(index == 4)
        multiRow.setVisible(index == 5)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAddInterval(checked: Boolean = false) {
        val dialog = DateIntervalDialog(this)
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            val interval = dialog.interval()
            multiIntervals.add(interval)
            multiList.addItem(intervalLabel(interval))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRemoveInterval(checked: Boolean = false) {
        val row = multiList.currentRow()
        if (row in multiIntervals.indices) {
            multiIntervals.removeAt(row)
            multiList.takeItem(row)
        }
    }

    override fun buildCondition(): FilterCondition {
        val intervals = when (modeCombo.currentIndex()) {
            0 -> listOf(DateInterval.TODAY)
            1 -> listOf(DateInterval.CURRENT_MONTH)
            2 -> listOf(DateInterval.CURRENT_QUARTER)
            3 -> listOf(DateInterval(DateBound.Relative(DateAnchor.YearStart), DateBound.Relative(DateAnchor.YearEnd)))
            4 -> listOf(DateInterval(fixedBound(fromEdit), fixedBound(toEdit)))
            else -> multiIntervals.toList().ifEmpty { listOf(DateInterval.CURRENT_MONTH) }
        }
        return DateCondition(intervals, emptyCheck.isChecked(), invertedCheck.isChecked())
    }

    override fun loadCondition(condition: FilterCondition) {
        condition as DateCondition
        emptyCheck.setChecked(condition.includeEmpty)
        invertedCheck.setChecked(condition.inverted)
        when {
            condition.intervals == listOf(DateInterval.TODAY) -> modeCombo.setCurrentIndex(0)
            condition.intervals == listOf(DateInterval.CURRENT_MONTH) -> modeCombo.setCurrentIndex(1)
            condition.intervals == listOf(DateInterval.CURRENT_QUARTER) -> modeCombo.setCurrentIndex(2)
            condition.intervals.size == 1 && condition.intervals.single().from is DateBound.Fixed -> {
                modeCombo.setCurrentIndex(4)
                val interval = condition.intervals.single()
                (interval.from as? DateBound.Fixed)?.let { fromEdit.setDate(toQDate(it.date)) }
                (interval.to as? DateBound.Fixed)?.let { toEdit.setDate(toQDate(it.date)) }
            }
            else -> {
                modeCombo.setCurrentIndex(5)
                multiIntervals.clear()
                multiList.clear()
                condition.intervals.forEach {
                    multiIntervals.add(it)
                    multiList.addItem(intervalLabel(it))
                }
            }
        }
    }

    override fun reset() {
        emptyCheck.setChecked(false)
        invertedCheck.setChecked(false)
        modeCombo.setCurrentIndex(1)
        multiIntervals.clear()
        multiList.clear()
    }

    private fun fixedBound(edit: QDateEdit): DateBound.Fixed {
        val d = edit.date()
        return DateBound.Fixed(LocalDate.of(d.year(), d.month(), d.day()))
    }

    private fun intervalLabel(interval: DateInterval): String {
        val from = (interval.from as? DateBound.Fixed)?.date?.let(Formatter::date) ?: "…"
        val to = (interval.to as? DateBound.Fixed)?.date?.let(Formatter::date) ?: "…"
        return "$from — $to"
    }
}

private fun toQDate(date: LocalDate): QDate = QDate(date.year, date.monthValue, date.dayOfMonth)

/** Small dialog for one fixed interval, added to the Date field's "Несколько интервалов" list. */
private class DateIntervalDialog(parent: QWidget?) : QDialog(parent) {
    private val fromEdit = QDateEdit()
    private val toEdit = QDateEdit()

    init {
        setWindowTitle(Strings.ADD_INTERVAL)
        val layout = QFormLayout(this)
        val today = toQDate(LocalDate.now())
        fromEdit.setCalendarPopup(true)
        fromEdit.setDate(today)
        toEdit.setCalendarPopup(true)
        toEdit.setDate(today)
        layout.addRow(Strings.FROM_DATE, fromEdit)
        layout.addRow(Strings.TO_DATE, toEdit)
        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel),
        )
        buttons.accepted.connect(this::accept)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    fun interval(): DateInterval {
        val f = fromEdit.date()
        val t = toEdit.date()
        return DateInterval(
            DateBound.Fixed(LocalDate.of(f.year(), f.month(), f.day())),
            DateBound.Fixed(LocalDate.of(t.year(), t.month(), t.day())),
        )
    }
}

/** Reference field editor: a checkable list of the dictionary's live entries. */
private class RefFieldEditor(private val refField: RefField, private val withSublevels: Boolean) : QWidget(), FieldEditor {
    override val widget: QWidget get() = this
    private val list = QListWidget()
    private val sublevelsCheck = QCheckBox(Strings.SHOW_SUBLEVELS)
    private val emptyCheck = QCheckBox(Strings.FLAG_EMPTY)
    private val invertedCheck = QCheckBox(Strings.FLAG_INVERTED)

    init {
        val layout = QVBoxLayout(this)
        layout.addWidget(list, 1)
        if (withSublevels) layout.addWidget(sublevelsCheck)
        layout.addWidget(emptyCheck)
        layout.addWidget(invertedCheck)
    }

    /** Rebuilds the list from (id, display label) pairs, keeping whatever ids were already checked. */
    fun setEntries(entries: List<Pair<Long, String>>) {
        val checked = checkedIds()
        list.clear()
        for ((id, label) in entries) {
            val item = QListWidgetItem(label)
            item.setFlags(Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsUserCheckable)
            item.setCheckState(if (id in checked) Qt.CheckState.Checked else Qt.CheckState.Unchecked)
            item.setData(Qt.ItemDataRole.UserRole, id)
            list.addItem(item)
        }
    }

    private fun checkedIds(): Set<Long> =
        (0 until list.count()).mapNotNullTo(LinkedHashSet()) { i ->
            val item = list.item(i)
            if (item.checkState() == Qt.CheckState.Checked) item.data(Qt.ItemDataRole.UserRole) as? Long else null
        }

    override fun buildCondition(): FilterCondition = RefCondition(
        refField, checkedIds(), withSublevels && sublevelsCheck.isChecked(), emptyCheck.isChecked(), invertedCheck.isChecked(),
    )

    override fun loadCondition(condition: FilterCondition) {
        condition as RefCondition
        emptyCheck.setChecked(condition.includeEmpty)
        invertedCheck.setChecked(condition.inverted)
        if (withSublevels) sublevelsCheck.setChecked(condition.withDescendants)
        for (i in 0 until list.count()) {
            val item = list.item(i)
            val id = item.data(Qt.ItemDataRole.UserRole) as? Long
            item.setCheckState(if (id != null && id in condition.ids) Qt.CheckState.Checked else Qt.CheckState.Unchecked)
        }
    }

    override fun reset() {
        emptyCheck.setChecked(false)
        invertedCheck.setChecked(false)
        sublevelsCheck.setChecked(false)
        for (i in 0 until list.count()) list.item(i).setCheckState(Qt.CheckState.Unchecked)
    }
}

/** Amount field editor: от/до, 0 meaning unbounded (`Summa` is always positive — dev_plan.md §5.1). */
private class AmountFieldEditor : QWidget(), FieldEditor {
    override val widget: QWidget get() = this
    private val fromSpin = QDoubleSpinBox()
    private val toSpin = QDoubleSpinBox()
    private val emptyCheck = QCheckBox(Strings.FLAG_EMPTY)
    private val invertedCheck = QCheckBox(Strings.FLAG_INVERTED)

    init {
        val layout = QFormLayout(this)
        for (spin in listOf(fromSpin, toSpin)) {
            spin.setDecimals(2)
            spin.setMinimum(0.0)
            spin.setMaximum(999999999.0)
            spin.setSpecialValueText(" ")
        }
        layout.addRow("${Strings.FROM_DATE}:", fromSpin)
        layout.addRow("${Strings.TO_DATE}:", toSpin)
        layout.addRow(emptyCheck)
        layout.addRow(invertedCheck)
    }

    override fun buildCondition(): FilterCondition = AmountCondition(
        fromSpin.value().takeIf { it > 0.0 }?.let(BigDecimal::valueOf),
        toSpin.value().takeIf { it > 0.0 }?.let(BigDecimal::valueOf),
        emptyCheck.isChecked(),
        invertedCheck.isChecked(),
    )

    override fun loadCondition(condition: FilterCondition) {
        condition as AmountCondition
        fromSpin.setValue(condition.min?.toDouble() ?: 0.0)
        toSpin.setValue(condition.max?.toDouble() ?: 0.0)
        emptyCheck.setChecked(condition.includeEmpty)
        invertedCheck.setChecked(condition.inverted)
    }

    override fun reset() {
        fromSpin.setValue(0.0)
        toSpin.setValue(0.0)
        emptyCheck.setChecked(false)
        invertedCheck.setChecked(false)
    }
}

/** Comment field editor: one substring box (always `TextField.Comment` — the panel's only text field). */
private class TextFieldEditor : QWidget(), FieldEditor {
    override val widget: QWidget get() = this
    private val lineEdit = QLineEdit()
    private val emptyCheck = QCheckBox(Strings.FLAG_EMPTY)
    private val invertedCheck = QCheckBox(Strings.FLAG_INVERTED)

    init {
        val layout = QVBoxLayout(this)
        layout.addWidget(lineEdit)
        layout.addWidget(emptyCheck)
        layout.addWidget(invertedCheck)
        layout.addStretch()
    }

    override fun buildCondition(): FilterCondition =
        TextCondition(TextField.Comment, lineEdit.text(), emptyCheck.isChecked(), invertedCheck.isChecked())

    override fun loadCondition(condition: FilterCondition) {
        condition as TextCondition
        lineEdit.setText(condition.substring)
        emptyCheck.setChecked(condition.includeEmpty)
        invertedCheck.setChecked(condition.inverted)
    }

    override fun reset() {
        lineEdit.setText("")
        emptyCheck.setChecked(false)
        invertedCheck.setChecked(false)
    }
}

/**
 * The «Фильтр» panel (dev_plan.md §6.3, FR-26…FR-28): a checkable field list on the left, one
 * editor page per field on the right, and buttons to apply, clear, save/load/favorite filters, and
 * promote the current field into the [QuickFilterPanel].
 */
class FilterPanel(
    private val controller: AppController,
    private val settings: AppSettings,
    parent: QWidget? = null,
) : QWidget(parent) {

    var quickFilterPanel: QuickFilterPanel? = null

    private val fieldList = QListWidget()
    private val stack = QStackedWidget()
    private val fieldItems = LinkedHashMap<FilterField, QListWidgetItem>()

    private val editors: Map<FilterField, FieldEditor> = mapOf(
        FilterField.Date to DateFieldEditor(),
        FilterField.OperationType to RefFieldEditor(RefField.OperationType, withSublevels = false),
        FilterField.BudgetItem to RefFieldEditor(RefField.BudgetItem, withSublevels = true),
        FilterField.Amount to AmountFieldEditor(),
        FilterField.Currency to RefFieldEditor(RefField.Currency, withSublevels = false),
        FilterField.Comment to TextFieldEditor(),
        FilterField.Counterparty to RefFieldEditor(RefField.Counterparty, withSublevels = false),
        FilterField.AccountFrom to RefFieldEditor(RefField.AccountFrom, withSublevels = false),
        FilterField.AccountTo to RefFieldEditor(RefField.AccountTo, withSublevels = false),
        FilterField.Account to RefFieldEditor(RefField.Account, withSublevels = false),
    )

    private val favoritesButton = QToolButton()
    private val favoritesMenu = QMenu(this)

    init {
        val layout = QHBoxLayout(this)

        for (field in FilterField.entries) {
            val item = QListWidgetItem(field.label)
            item.setFlags(Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsUserCheckable)
            item.setCheckState(Qt.CheckState.Unchecked)
            if (field.bold) item.setFont(QFont().apply { setBold(true) })
            fieldList.addItem(item)
            fieldItems[field] = item
            stack.addWidget(editors.getValue(field).widget)
        }
        fieldList.currentRowChanged.connect(this::onFieldSelected)
        fieldList.setCurrentRow(0)
        layout.addWidget(fieldList)

        val rightLayout = QVBoxLayout()
        rightLayout.addWidget(stack, 1)

        val buttonsRow = QHBoxLayout()
        val applyButton = QPushButton(Strings.APPLY)
        applyButton.clicked.connect(this::applyFilterPanel)
        buttonsRow.addWidget(applyButton)

        val clearButton = QPushButton(Strings.CLEAR_FILTER)
        clearButton.setShortcut(QKeySequence("F8"))
        clearButton.clicked.connect(this::clearFilterPanel)
        buttonsRow.addWidget(clearButton)

        val saveButton = QPushButton(Strings.SAVE_FILTER)
        saveButton.clicked.connect(this::saveCurrentFilter)
        buttonsRow.addWidget(saveButton)

        val loadButton = QPushButton(Strings.LOAD_FILTER)
        loadButton.clicked.connect(this::showLoadFilterDialog)
        buttonsRow.addWidget(loadButton)

        favoritesButton.setText(Strings.FAVORITES)
        favoritesButton.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        favoritesButton.setMenu(favoritesMenu)
        refreshFavoritesMenu()
        buttonsRow.addWidget(favoritesButton)

        val quickButton = QPushButton(Strings.TO_QUICK_FILTER)
        quickButton.setShortcut(QKeySequence("Ctrl+Q"))
        quickButton.clicked.connect(this::promoteCurrentFieldToQuickFilter)
        buttonsRow.addWidget(quickButton)

        rightLayout.addLayout(buttonsRow)
        layout.addLayout(rightLayout, 1)
    }

    private fun isFieldChecked(field: FilterField): Boolean =
        fieldItems.getValue(field).checkState() == Qt.CheckState.Checked

    private fun setFieldChecked(field: FilterField, checked: Boolean) {
        fieldItems.getValue(field).setCheckState(if (checked) Qt.CheckState.Checked else Qt.CheckState.Unchecked)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onFieldSelected(row: Int) {
        if (row in FilterField.entries.indices) stack.setCurrentIndex(row)
    }

    /** Repopulates every reference field's live entries from the currently open database. */
    fun refreshData() {
        val dict = controller.dictionary.state.value
        (editors.getValue(FilterField.OperationType) as RefFieldEditor).setEntries(
            dict.operationTypes.filter { !it.deleted }.map { it.id to (it.name ?: "id:${it.id}") },
        )
        val tree = dict.budgetTree
        (editors.getValue(FilterField.BudgetItem) as RefFieldEditor).setEntries(
            tree.preorder().filter { !it.deleted }.map { item ->
                val depth = (tree.path(item.id).size - 1).coerceAtLeast(0)
                item.id to ("  ".repeat(depth) + (item.name ?: "id:${item.id}"))
            },
        )
        (editors.getValue(FilterField.Currency) as RefFieldEditor).setEntries(
            dict.currencies.filter { !it.deleted }.map { it.id to (it.name ?: "id:${it.id}") },
        )
        (editors.getValue(FilterField.Counterparty) as RefFieldEditor).setEntries(
            dict.counterparties.filter { !it.deleted }.map { it.id to (it.name ?: "id:${it.id}") },
        )
        val accounts = dict.accounts.filter { !it.deleted }.map { it.id to (it.name ?: "id:${it.id}") }
        (editors.getValue(FilterField.AccountFrom) as RefFieldEditor).setEntries(accounts)
        (editors.getValue(FilterField.AccountTo) as RefFieldEditor).setEntries(accounts)
        (editors.getValue(FilterField.Account) as RefFieldEditor).setEntries(accounts)
    }

    @Suppress("UNUSED_PARAMETER")
    fun applyFilterPanel(checked: Boolean = false) {
        val conditions = FilterField.entries.filter { isFieldChecked(it) }.map { editors.getValue(it).buildCondition() }
        controller.applyFilter(JournalFilter(conditions))
    }

    @Suppress("UNUSED_PARAMETER")
    fun clearFilterPanel(checked: Boolean = false) {
        FilterField.entries.forEach {
            setFieldChecked(it, false)
            editors.getValue(it).reset()
        }
        controller.applyFilter(JournalFilter.NONE)
    }

    @Suppress("UNUSED_PARAMETER")
    fun promoteCurrentFieldToQuickFilter(checked: Boolean = false) {
        val field = FilterField.entries.getOrNull(fieldList.currentRow()) ?: return
        quickFilterPanel?.promote(editors.getValue(field).buildCondition())
    }

    @Suppress("UNUSED_PARAMETER")
    fun saveCurrentFilter(checked: Boolean = false) {
        val name = QInputDialog.getText(this, Strings.SAVE_FILTER, Strings.SAVE_FILTER_PROMPT)
        if (name.isNullOrBlank()) return
        val conditions = FilterField.entries.filter { isFieldChecked(it) }.map { editors.getValue(it).buildCondition() }
        val json = JournalFilterJson.encodeToString(JournalFilter(conditions))
        settings.addOrReplaceSavedFilter(SavedFilter(name.trim(), json))
        refreshFavoritesMenu()
    }

    @Suppress("UNUSED_PARAMETER")
    fun showLoadFilterDialog(checked: Boolean = false) {
        val dialog = LoadFilterDialog(this, settings)
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            dialog.selectedFilter()?.let { loadFilter(JournalFilterJson.decodeFromString(it.filterJson)) }
        }
        refreshFavoritesMenu()
    }

    private fun loadFilter(filter: JournalFilter) {
        FilterField.entries.forEach { setFieldChecked(it, false) }
        for (condition in filter.conditions) {
            val field = fieldOf(condition)
            editors.getValue(field).loadCondition(condition)
            setFieldChecked(field, true)
        }
        controller.applyFilter(filter)
    }

    private fun refreshFavoritesMenu() {
        favoritesMenu.clear()
        val favorites = settings.savedFilters.filter { it.favorite }
        if (favorites.isEmpty()) {
            favoritesMenu.addAction(Strings.NO_SAVED_FILTERS)?.setEnabled(false)
            return
        }
        for (f in favorites) {
            val action = favoritesMenu.addAction(f.name)
            action?.triggered?.connect(QMetaObject.Slot0 { loadFilter(JournalFilterJson.decodeFromString(f.filterJson)) })
        }
    }
}

/** Dialog listing saved filters (§6.3 "Загрузить фильтр"), with delete and favorite-toggle. */
private class LoadFilterDialog(parent: QWidget?, private val settings: AppSettings) : QDialog(parent) {
    private val list = QListWidget()
    private var filters: List<SavedFilter> = emptyList()

    init {
        setWindowTitle(Strings.LOAD_FILTER_TITLE)
        val layout = QVBoxLayout(this)
        layout.addWidget(list, 1)
        refresh()

        val actionRow = QHBoxLayout()
        val favoriteButton = QPushButton(Strings.FAVORITES)
        favoriteButton.clicked.connect(this::onToggleFavorite)
        actionRow.addWidget(favoriteButton)
        val deleteButton = QPushButton(Strings.DELETE)
        deleteButton.clicked.connect(this::onDelete)
        actionRow.addWidget(deleteButton)
        layout.addLayout(actionRow)

        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel),
        )
        buttons.accepted.connect(this::accept)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    private fun refresh() {
        filters = settings.savedFilters
        list.clear()
        if (filters.isEmpty()) {
            list.addItem(Strings.NO_SAVED_FILTERS)
            return
        }
        for (f in filters) list.addItem(if (f.favorite) "★ ${f.name}" else f.name)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDelete(checked: Boolean = false) {
        val f = filters.getOrNull(list.currentRow()) ?: return
        settings.removeSavedFilter(f.name)
        refresh()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onToggleFavorite(checked: Boolean = false) {
        val f = filters.getOrNull(list.currentRow()) ?: return
        settings.setSavedFilterFavorite(f.name, !f.favorite)
        refresh()
    }

    fun selectedFilter(): SavedFilter? = filters.getOrNull(list.currentRow())
}
