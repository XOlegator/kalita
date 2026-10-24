// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.Qt
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QListWidget
import io.qt.widgets.QListWidgetItem
import io.qt.widgets.QPushButton
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.AmountCondition
import kalita.core.DateCondition
import kalita.core.FilterCondition
import kalita.core.RefCondition
import kalita.core.TextCondition
import kalita.presentation.Formatter
import kalita.presentation.Strings

/**
 * The «Быстрый фильтр» panel (dev_plan.md §6.2): conditions promoted from [FilterPanel] via
 * "В быстрый фильтр" (Ctrl+Q), each toggled on or off without reopening the full panel. Enabled
 * conditions are sent to [AppController.setQuickFilterConditions] on every change.
 */
class QuickFilterPanel(
    private val controller: AppController,
    parent: QWidget? = null,
) : QWidget(parent) {

    private data class Entry(val condition: FilterCondition, var enabled: Boolean = true)

    private val entries = mutableListOf<Entry>()
    private val list = QListWidget()

    init {
        val layout = QVBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)
        list.itemChanged.connect(this::onItemChanged)
        layout.addWidget(list, 1)

        val removeButton = QPushButton(Strings.DELETE)
        removeButton.clicked.connect(this::onRemoveSelected)
        val row = QHBoxLayout()
        row.addWidget(removeButton)
        layout.addLayout(row)
    }

    /** Adds or replaces [condition] by field and shows it, enabled, in the list. */
    fun promote(condition: FilterCondition) {
        entries.removeAll { it.condition.fieldKey() == condition.fieldKey() }
        entries.add(Entry(condition))
        rebuild()
        notifyController()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRemoveSelected(checked: Boolean = false) {
        val row = list.currentRow()
        if (row in entries.indices) {
            entries.removeAt(row)
            rebuild()
            notifyController()
        }
    }

    private fun onItemChanged(item: QListWidgetItem) {
        val row = list.row(item)
        if (row in entries.indices) {
            entries[row].enabled = item.checkState() == Qt.CheckState.Checked
            notifyController()
        }
    }

    private fun rebuild() {
        list.blockSignals(true)
        list.clear()
        for (entry in entries) {
            val item = QListWidgetItem(summarize(entry.condition))
            item.setFlags(Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsUserCheckable)
            item.setCheckState(if (entry.enabled) Qt.CheckState.Checked else Qt.CheckState.Unchecked)
            list.addItem(item)
        }
        list.blockSignals(false)
    }

    private fun notifyController() {
        controller.setQuickFilterConditions(entries.filter { it.enabled }.map { it.condition })
    }

    private fun summarize(condition: FilterCondition): String {
        val dict = controller.dictionary.state.value
        return when (condition) {
            is DateCondition -> "${Strings.DATE}: ${condition.intervals.size}"
            is AmountCondition -> "${Strings.AMOUNT}: ${condition.min?.let(Formatter::amount) ?: "…"} – ${condition.max?.let(Formatter::amount) ?: "…"}"
            is TextCondition -> "${Strings.COMMENT} ⊃ «${condition.substring}»"
            is RefCondition -> {
                val label = when (condition.field) {
                    kalita.core.RefField.OperationType -> Strings.OPERATION_TYPE
                    kalita.core.RefField.BudgetItem -> Strings.BUDGET_ITEM
                    kalita.core.RefField.Currency -> Strings.CURRENCY
                    kalita.core.RefField.Counterparty -> Strings.COUNTERPARTY
                    kalita.core.RefField.AccountFrom -> Strings.ACCOUNT_FROM
                    kalita.core.RefField.AccountTo -> Strings.ACCOUNT_TO
                    kalita.core.RefField.Account -> Strings.ACCOUNT
                }
                val names = condition.ids.mapNotNull {
                    when (condition.field) {
                        kalita.core.RefField.OperationType -> dict.operationTypes.firstOrNull { t -> t.id == it }?.name
                        kalita.core.RefField.BudgetItem -> dict.budgetTree.item(it)?.name
                        kalita.core.RefField.Currency -> dict.currencies.firstOrNull { c -> c.id == it }?.name
                        kalita.core.RefField.Counterparty -> dict.counterparties.firstOrNull { c -> c.id == it }?.name
                        else -> dict.accounts.firstOrNull { a -> a.id == it }?.name
                    }
                }
                if (names.isEmpty()) "$label: —" else "$label: ${names.joinToString(", ")}"
            }
        }
    }
}
