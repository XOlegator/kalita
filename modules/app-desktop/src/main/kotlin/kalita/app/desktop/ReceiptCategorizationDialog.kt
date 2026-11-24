// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.widgets.QCheckBox
import io.qt.widgets.QComboBox
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QMessageBox
import io.qt.widgets.QPushButton
import io.qt.widgets.QTableWidget
import io.qt.widgets.QTabWidget
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.agent.api.BuiltInReceiptDictionaryEntry
import kalita.agent.api.ReceiptCategoryRule
import kalita.agent.api.ReceiptCategorizationStore
import kalita.agent.api.ReceiptDictionaryEntry
import kalita.agent.api.budgetPath
import kalita.core.BudgetItem
import kalita.core.Dataset
import kalita.core.OperationKind
import kalita.presentation.Strings
import java.util.Locale

/** Edits receipt categorization settings kept beside the open database, never in `Data.mdb`. */
class ReceiptCategorizationDialog(
    parent: QWidget?,
    private val dataset: Dataset,
    private val store: ReceiptCategorizationStore,
    initialRules: List<ReceiptCategoryRule>,
    initialDictionary: List<ReceiptDictionaryEntry>,
    initialBuiltIn: List<BuiltInReceiptDictionaryEntry>,
) : QDialog(parent) {
    private data class RuleRow(val merchant: QLineEdit, val contains: QLineEdit, val item: QComboBox, val itemIds: List<Long?>)
    private data class DictionaryRow(val text: QLineEdit, val item: QComboBox, val itemIds: List<Long?>)
    private data class BuiltInRow(
        val entry: BuiltInReceiptDictionaryEntry,
        val meaning: QComboBox,
        val enabled: QCheckBox,
        var resetToDefault: Boolean = false,
    )

    private val expenseItems = activeExpenseItems(dataset)
    private val expenseIds = expenseItems.mapTo(HashSet()) { it.id }
    private val validExpenseNames = expenseItems.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
        .groupBy { it.lowercase(Locale.ROOT) }.values.filter { it.size == 1 }.map { it.single() }
    private val expenseNames = validExpenseNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    private val ruleTable = QTableWidget(0, 3)
    private val dictionaryTable = QTableWidget(0, 2)
    private val builtInTable = QTableWidget(0, 5)
    private val ruleRows = ArrayList<RuleRow>()
    private val dictionaryRows = ArrayList<DictionaryRow>()
    private val builtInRows = ArrayList<BuiltInRow>()

    init {
        setWindowTitle(Strings.RECEIPT_CATEGORIZATION_TITLE)
        resize(900, 600)
        val layout = QVBoxLayout(this)
        val tabs = QTabWidget()
        layout.addWidget(tabs, 1)

        val rulesPage = QWidget()
        val rulesLayout = QVBoxLayout(rulesPage)
        val hint = QLabel(Strings.RECEIPT_HINT)
        hint.setWordWrap(true)
        rulesLayout.addWidget(hint)
        configureTable(ruleTable, listOf(Strings.RECEIPT_RULE_MERCHANT, Strings.RECEIPT_CONTAINS, Strings.BUDGET_ITEM))
        rulesLayout.addWidget(ruleTable, 1)
        val ruleTools = QHBoxLayout()
        addButton(ruleTools, Strings.ADD) { addRule(null) }
        addButton(ruleTools, Strings.DELETE) { removeRule() }
        addButton(ruleTools, Strings.MOVE_UP) { moveRule(-1) }
        addButton(ruleTools, Strings.MOVE_DOWN) { moveRule(1) }
        ruleTools.addStretch(1)
        rulesLayout.addLayout(ruleTools)
        tabs.addTab(rulesPage, Strings.RECEIPT_RULES)

        val dictionaryPage = QWidget()
        val dictionaryLayout = QVBoxLayout(dictionaryPage)
        val dictionaryHint = QLabel(Strings.RECEIPT_DICTIONARY_HINT)
        dictionaryHint.setWordWrap(true)
        dictionaryLayout.addWidget(dictionaryHint)
        configureTable(dictionaryTable, listOf(Strings.RECEIPT_PHRASE, Strings.BUDGET_ITEM))
        dictionaryLayout.addWidget(dictionaryTable, 1)
        val dictionaryTools = QHBoxLayout()
        addButton(dictionaryTools, Strings.ADD) { addDictionaryEntry(null) }
        addButton(dictionaryTools, Strings.DELETE) { removeDictionaryEntry() }
        dictionaryTools.addStretch(1)
        dictionaryLayout.addLayout(dictionaryTools)
        tabs.addTab(dictionaryPage, Strings.RECEIPT_DICTIONARY)

        val builtInPage = QWidget()
        val builtInLayout = QVBoxLayout(builtInPage)
        val builtInHint = QLabel(Strings.RECEIPT_BUILTIN_HINT)
        builtInHint.setWordWrap(true)
        builtInLayout.addWidget(builtInHint)
        configureTable(
            builtInTable,
            listOf(Strings.RECEIPT_PHRASE, Strings.RECEIPT_DEFAULT, Strings.RECEIPT_MEANING, Strings.RECEIPT_ENABLED, Strings.RECEIPT_RESET),
        )
        builtInLayout.addWidget(builtInTable, 1)
        tabs.addTab(builtInPage, Strings.RECEIPT_BUILTIN)

        initialRules.forEach(::addRule)
        initialDictionary.forEach(::addDictionaryEntry)
        initialBuiltIn.forEach(::addBuiltInEntry)

        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel),
        )
        buttons.button(QDialogButtonBox.StandardButton.Ok)?.setText(Strings.SAVE)
        buttons.button(QDialogButtonBox.StandardButton.Cancel)?.setText(Strings.CANCEL)
        buttons.accepted.connect(QMetaObject.Slot0 { save() })
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    private fun configureTable(table: QTableWidget, headers: List<String>) {
        table.setHorizontalHeaderLabels(headers)
        table.verticalHeader()?.setVisible(false)
        table.horizontalHeader()?.setStretchLastSection(true)
    }

    private fun addButton(layout: QHBoxLayout, text: String, action: () -> Unit) {
        val button = QPushButton(text)
        button.clicked.connect(QMetaObject.Slot0 { action() })
        layout.addWidget(button)
    }

    private fun budgetItemCombo(selectedId: Long?): Pair<QComboBox, List<Long?>> {
        val combo = QComboBox()
        val ids = ArrayList<Long?>()
        combo.addItem(Strings.RECEIPT_NO_ITEM)
        ids += null
        for (item in expenseItems) {
            combo.addItem(budgetPath(dataset, item.id) ?: item.name ?: "id:${item.id}")
            ids += item.id
        }
        if (selectedId != null && selectedId !in expenseIds) {
            combo.addItem(Strings.receiptUnavailableItem(selectedId))
            ids += selectedId
        }
        combo.setCurrentIndex(ids.indexOf(selectedId).coerceAtLeast(0))
        return combo to ids
    }

    private fun addRule(rule: ReceiptCategoryRule?) {
        val row = ruleRows.size
        ruleTable.insertRow(row)
        val merchant = QLineEdit(rule?.merchant.orEmpty())
        val contains = QLineEdit(rule?.contains.orEmpty())
        val (item, itemIds) = budgetItemCombo(rule?.budgetItemId)
        ruleTable.setCellWidget(row, 0, merchant)
        ruleTable.setCellWidget(row, 1, contains)
        ruleTable.setCellWidget(row, 2, item)
        ruleRows += RuleRow(merchant, contains, item, itemIds)
        ruleTable.selectRow(row)
    }

    private fun rules(): List<ReceiptCategoryRule> = ruleRows.map { row ->
        ReceiptCategoryRule(
            merchant = row.merchant.text().trim().takeIf(String::isNotEmpty),
            contains = row.contains.text().trim().takeIf(String::isNotEmpty),
            budgetItemId = row.itemIds.getOrNull(row.item.currentIndex()) ?: -1L,
        )
    }

    private fun removeRule() {
        val index = ruleTable.currentRow()
        if (index !in ruleRows.indices) return
        reloadRules(rules().filterIndexed { row, _ -> row != index }, index)
    }

    private fun moveRule(delta: Int) {
        val index = ruleTable.currentRow()
        val target = index + delta
        if (index !in ruleRows.indices || target !in ruleRows.indices) return
        val reordered = rules().toMutableList()
        val moved = reordered.removeAt(index)
        reordered.add(target, moved)
        reloadRules(reordered, target)
    }

    private fun reloadRules(rules: List<ReceiptCategoryRule>, selected: Int) {
        ruleTable.setRowCount(0)
        ruleRows.clear()
        rules.forEach(::addRule)
        if (selected in ruleRows.indices) ruleTable.selectRow(selected)
    }

    private fun addDictionaryEntry(entry: ReceiptDictionaryEntry?) {
        val row = dictionaryRows.size
        dictionaryTable.insertRow(row)
        val text = QLineEdit(entry?.text.orEmpty())
        val (item, itemIds) = budgetItemCombo(entry?.budgetItemId)
        dictionaryTable.setCellWidget(row, 0, text)
        dictionaryTable.setCellWidget(row, 1, item)
        dictionaryRows += DictionaryRow(text, item, itemIds)
        dictionaryTable.selectRow(row)
    }

    private fun dictionaryEntries(): List<ReceiptDictionaryEntry> = dictionaryRows.map { row ->
        ReceiptDictionaryEntry(
            text = row.text.text().trim(),
            budgetItemId = row.itemIds.getOrNull(row.item.currentIndex()) ?: -1L,
        )
    }

    private fun removeDictionaryEntry() {
        val index = dictionaryTable.currentRow()
        if (index !in dictionaryRows.indices) return
        dictionaryRows.removeAt(index)
        dictionaryTable.removeRow(index)
        dictionaryTable.selectRow(index.coerceAtMost(dictionaryRows.lastIndex))
    }

    private fun addBuiltInEntry(entry: BuiltInReceiptDictionaryEntry) {
        val row = builtInRows.size
        builtInTable.insertRow(row)
        builtInTable.setCellWidget(row, 0, QLabel(entry.text))
        builtInTable.setCellWidget(row, 1, QLabel(entry.defaultMeaning))

        val meaning = QComboBox()
        val meanings = linkedSetOf(entry.defaultMeaning)
        meanings.addAll(validExpenseNames)
        entry.meaning?.let { meanings.add(it) }
        meanings.forEach(meaning::addItem)
        if (entry.meaning != null) meaning.setCurrentIndex((0 until meaning.count()).firstOrNull { meaning.itemText(it) == entry.meaning } ?: 0)
        else meaning.setCurrentIndex(0)
        builtInTable.setCellWidget(row, 2, meaning)

        val enabled = QCheckBox(Strings.RECEIPT_ENABLED)
        enabled.setChecked(entry.meaning != null)
        meaning.setEnabled(entry.meaning != null)
        enabled.toggled.connect(QMetaObject.Slot1 { checked: Boolean -> meaning.setEnabled(checked) })
        builtInTable.setCellWidget(row, 3, enabled)

        val reset = QPushButton(Strings.RECEIPT_RESET)
        reset.clicked.connect(QMetaObject.Slot0 {
            meaning.setCurrentIndex((0 until meaning.count()).firstOrNull { meaning.itemText(it) == entry.defaultMeaning } ?: 0)
            enabled.setChecked(true)
            builtInRows.getOrNull(row)?.resetToDefault = true
        })
        builtInTable.setCellWidget(row, 4, reset)
        builtInRows += BuiltInRow(entry, meaning, enabled)
    }

    private fun invalidRuleRows(): List<Int> = rules().withIndex().filter { (_, rule) ->
        (rule.merchant == null && rule.contains == null) ||
            (rule.merchant?.let { !hasReceiptKey(it) } == true) ||
            (rule.contains?.let { !hasReceiptKey(it) } == true) ||
            rule.budgetItemId !in expenseIds
    }.map { it.index + 1 }

    private fun invalidDictionaryRows(): List<Int> = dictionaryEntries().withIndex().filter { (_, entry) ->
        !hasReceiptKey(entry.text) || entry.budgetItemId !in expenseIds
    }.map { it.index + 1 }

    private fun invalidBuiltInRows(): List<Int> = builtInRows.withIndex().filter { (_, row) ->
        row.enabled.isChecked && row.meaning.currentText().trim().let { value ->
            value.isEmpty() || (value != row.entry.defaultMeaning && value.lowercase(Locale.ROOT) !in expenseNames)
        }
    }.map { it.index + 1 }

    private fun save() {
        val badRules = invalidRuleRows()
        if (badRules.isNotEmpty()) {
            showInvalid(Strings.RECEIPT_INVALID, badRules)
            return
        }
        val badDictionary = invalidDictionaryRows()
        if (badDictionary.isNotEmpty()) {
            QMessageBox.warning(this, Strings.RECEIPT_CATEGORIZATION_TITLE, Strings.receiptDictionaryInvalid(badDictionary))
            return
        }
        val badBuiltIn = invalidBuiltInRows()
        if (badBuiltIn.isNotEmpty()) {
            QMessageBox.warning(this, Strings.RECEIPT_CATEGORIZATION_TITLE, Strings.receiptBuiltInInvalid(badBuiltIn))
            return
        }

        var savedAnything = false
        try {
            store.replaceRules(rules())
            savedAnything = true
            store.replaceDictionary(dictionaryEntries())
            for (row in builtInRows) {
                val value = row.meaning.currentText().trim().takeIf { row.enabled.isChecked }
                if (row.resetToDefault && value == row.entry.defaultMeaning) {
                    store.resetBuiltInDictionaryEntry(row.entry.text)
                } else if (value != row.entry.meaning) {
                    store.setBuiltInDictionaryEntry(row.entry.text, value)
                }
                savedAnything = true
            }
            accept()
        } catch (e: Exception) {
            val details = e.message ?: e.toString()
            val message = if (savedAnything) {
                "${Strings.RECEIPT_ERROR}\n$details\n\n${Strings.RECEIPT_PARTIAL_SAVE}"
            } else {
                "${Strings.RECEIPT_ERROR}\n$details"
            }
            QMessageBox.critical(this, Strings.RECEIPT_CATEGORIZATION_TITLE, message)
        }
    }

    private fun showInvalid(message: String, rows: List<Int>) {
        QMessageBox.warning(this, Strings.RECEIPT_CATEGORIZATION_TITLE, "$message\n${rows.joinToString()}")
    }

    companion object {
        fun edit(parent: QWidget, controller: AppController) {
            val ready = controller.state.value as? AppState.Ready ?: run {
                QMessageBox.information(parent, Strings.RECEIPT_CATEGORIZATION_TITLE, Strings.RECEIPT_NO_DATABASE)
                return
            }
            val directory = controller.databaseContext()?.dataDirectory ?: run {
                QMessageBox.information(parent, Strings.RECEIPT_CATEGORIZATION_TITLE, Strings.RECEIPT_NO_DATABASE)
                return
            }
            val store = ReceiptCategorizationStore(directory)
            try {
                ReceiptCategorizationDialog(
                    parent,
                    ready.dataset,
                    store,
                    store.rules(),
                    store.dictionary(),
                    store.builtInDictionary(),
                ).exec()
            } catch (e: Exception) {
                QMessageBox.critical(
                    parent,
                    Strings.RECEIPT_CATEGORIZATION_TITLE,
                    "${Strings.RECEIPT_ERROR}\n${e.message ?: e}",
                )
            }
        }

        private fun hasReceiptKey(value: String): Boolean = value.any(Char::isLetterOrDigit)

        internal fun activeExpenseItems(dataset: Dataset): List<BudgetItem> {
            val expenseRoots = dataset.operationTypes.filter { !it.deleted && it.kind == OperationKind.Expense }
                .mapNotNull { it.budgetRootId }.toSet()
            val otherRoots = dataset.operationTypes.filter { !it.deleted && it.kind != OperationKind.Expense }
                .mapNotNull { it.budgetRootId }.toSet()
            if (expenseRoots.isEmpty()) return emptyList()

            fun isActiveExpense(item: BudgetItem): Boolean {
                val visited = HashSet<Long>()
                var current: BudgetItem? = item
                var inExpense = false
                var inOther = false
                while (current != null) {
                    if (current.deleted || !visited.add(current.id)) return false
                    if (current.id in expenseRoots) inExpense = true
                    if (current.id in otherRoots) inOther = true
                    val parentId = current.parentId ?: break
                    current = dataset.budgetItem(parentId) ?: return false
                }
                return inExpense && !inOther
            }

            return dataset.budgetItems.filter(::isActiveExpense)
                .sortedWith(compareBy({ budgetPath(dataset, it.id).orEmpty().lowercase() }, { it.id }))
        }
    }
}