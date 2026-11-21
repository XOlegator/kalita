// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QComboBox
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QMessageBox
import io.qt.widgets.QPushButton
import io.qt.widgets.QTableWidget
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.BudgetItem
import kalita.core.Dataset
import kalita.plugin.reconcile.CategoryRule
import kalita.plugin.reconcile.CategoryRuleStore
import kalita.presentation.Strings

/**
 * «Правила категоризации…» (dev_plan.md §11.3 step 5): the user's own «если в описании/у
 * продавца/MCC … — статья, кто/кому» rules, applied to new bank lines before the journal's
 * history. One table row per rule, top to bottom is the priority (the first match wins).
 */
class CategoryRulesDialog(
    parent: QWidget?,
    private val dataset: Dataset,
    private val store: CategoryRuleStore,
) : QDialog(parent) {
    private class RowWidgets(val field: QComboBox, val match: QComboBox, val pattern: QLineEdit, val item: QComboBox, val who: QComboBox)

    private val items: List<BudgetItem?> = budgetItemChoices(dataset, null).map { it?.first }
    private val itemLabels: List<String> = budgetItemChoices(dataset, null).map { it?.second ?: "—" }
    private val counterparties = dataset.counterparties.filter { !it.deleted }
    private val table = QTableWidget(0, 5)
    private val rows = ArrayList<RowWidgets>()

    init {
        setWindowTitle(Strings.RULES_TITLE)
        resize(900, 480)
        val layout = QVBoxLayout(this)
        val hint = QLabel(Strings.RULES_HINT)
        hint.setWordWrap(true)
        layout.addWidget(hint)

        table.setHorizontalHeaderLabels(listOf(Strings.RULE_FIELD, Strings.RULE_MATCH, Strings.RULE_PATTERN, Strings.BUDGET_ITEM, Strings.COUNTERPARTY))
        table.verticalHeader().setVisible(false)
        table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        table.horizontalHeader().setStretchLastSection(true)
        layout.addWidget(table, 1)

        val tools = QHBoxLayout()
        fun button(text: String, action: () -> Unit) = QPushButton(text).also { b ->
            b.clicked.connect(QMetaObject.Slot0 { action() })
            tools.addWidget(b)
        }
        button(Strings.ADD) { addRow(null); table.selectRow(rows.size - 1) }
        button(Strings.DELETE) { removeSelected() }
        button(Strings.MOVE_UP) { moveSelected(-1) }
        button(Strings.MOVE_DOWN) { moveSelected(+1) }
        tools.addStretch(1)
        layout.addLayout(tools)

        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel))
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText(Strings.CANCEL)
        buttons.accepted.connect(QMetaObject.Slot0 { onOk() })
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)

        store.read().forEach(::addRow)
        table.resizeColumnsToContents()
    }

    private fun addRow(rule: CategoryRule?) {
        val i = rows.size
        table.insertRow(i)
        val field = QComboBox().also { c -> FIELDS.forEach { c.addItem(fieldLabel(it)) } }
        val match = QComboBox().also { c -> MATCHES.forEach { c.addItem(matchLabel(it)) } }
        val pattern = QLineEdit()
        val item = QComboBox().also { c -> itemLabels.forEach { c.addItem(it) } }
        val who = QComboBox().also { c ->
            c.addItem("—")
            counterparties.forEach { c.addItem(it.name ?: "id:${it.id}") }
        }
        if (rule != null) {
            field.setCurrentIndex(FIELDS.indexOf(rule.field))
            match.setCurrentIndex(MATCHES.indexOf(rule.match))
            pattern.setText(rule.pattern)
            items.indexOfFirst { it != null && it.id == rule.budgetItemId }.takeIf { it > 0 }?.let(item::setCurrentIndex)
            counterparties.indexOfFirst { it.id == rule.counterpartyId }.takeIf { it >= 0 }?.let { who.setCurrentIndex(it + 1) }
        } else {
            field.setCurrentIndex(FIELDS.indexOf(CategoryRule.Field.MERCHANT))
        }
        table.setCellWidget(i, 0, field)
        table.setCellWidget(i, 1, match)
        table.setCellWidget(i, 2, pattern)
        table.setCellWidget(i, 3, item)
        table.setCellWidget(i, 4, who)
        rows += RowWidgets(field, match, pattern, item, who)
    }

    private fun ruleAt(i: Int): CategoryRule {
        val r = rows[i]
        return CategoryRule(
            field = FIELDS[r.field.currentIndex()],
            match = MATCHES[r.match.currentIndex()],
            pattern = r.pattern.text().trim(),
            budgetItemId = items.getOrNull(r.item.currentIndex())?.id,
            counterpartyId = counterparties.getOrNull(r.who.currentIndex() - 1)?.id,
        )
    }

    private fun selected(): Int = table.currentRow().takeIf { it in rows.indices } ?: -1

    /** Rebuilds the table from [rules] — simpler and safer than shuffling cell widgets between rows. */
    private fun reload(rules: List<CategoryRule>, select: Int) {
        table.setRowCount(0)
        rows.clear()
        rules.forEach(::addRow)
        if (select in rules.indices) table.selectRow(select)
    }

    private fun current(): List<CategoryRule> = rows.indices.map(::ruleAt)

    private fun removeSelected() {
        val i = selected().takeIf { it >= 0 } ?: return
        reload(current().filterIndexed { idx, _ -> idx != i }, minOf(i, rows.size - 2))
    }

    private fun moveSelected(delta: Int) {
        val i = selected().takeIf { it >= 0 } ?: return
        val j = i + delta
        if (j !in rows.indices) return
        val list = current().toMutableList()
        list[i] = list[j].also { list[j] = list[i] }
        reload(list, j)
    }

    private fun onOk() {
        val rules = current()
        val bad = rules.withIndex().filter { !it.value.usable }.map { it.index + 1 }
        if (bad.isNotEmpty()) {
            QMessageBox.warning(this, Strings.RULES_TITLE, Strings.rulesIncomplete(bad))
            return
        }
        store.write(rules)
        accept()
    }

    companion object {
        private val FIELDS = listOf(CategoryRule.Field.MERCHANT, CategoryRule.Field.DESCRIPTION, CategoryRule.Field.MCC)
        private val MATCHES = listOf(CategoryRule.Match.CONTAINS, CategoryRule.Match.STARTS_WITH, CategoryRule.Match.EQUALS)

        fun fieldLabel(f: CategoryRule.Field): String = when (f) {
            CategoryRule.Field.MERCHANT -> Strings.RULE_FIELD_MERCHANT
            CategoryRule.Field.DESCRIPTION -> Strings.RULE_FIELD_DESCRIPTION
            CategoryRule.Field.MCC -> Strings.RULE_FIELD_MCC
        }

        fun matchLabel(m: CategoryRule.Match): String = when (m) {
            CategoryRule.Match.CONTAINS -> Strings.RULE_MATCH_CONTAINS
            CategoryRule.Match.STARTS_WITH -> Strings.RULE_MATCH_STARTS
            CategoryRule.Match.EQUALS -> Strings.RULE_MATCH_EQUALS
        }

        /** Opens the editor over the open database's rules. */
        fun edit(parent: QWidget, controller: AppController) {
            val ready = controller.state.value as? AppState.Ready ?: run {
                QMessageBox.information(parent, Strings.RULES_TITLE, "Сначала откройте базу.")
                return
            }
            val store = controller.categoryRules() ?: return
            CategoryRulesDialog(parent, ready.dataset, store).exec()
        }
    }
}
