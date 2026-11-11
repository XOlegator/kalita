// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QCheckBox
import io.qt.widgets.QComboBox
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QFileDialog
import io.qt.widgets.QFormLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QMessageBox
import io.qt.widgets.QTabWidget
import io.qt.widgets.QTableWidget
import io.qt.widgets.QTableWidgetItem
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.BudgetItem
import kalita.core.Dataset
import kalita.core.Operation
import kalita.plugin.api.BankTransaction
import kalita.plugin.reconcile.BankMappingStore
import kalita.plugin.reconcile.Decision
import kalita.plugin.reconcile.LinkKind
import kalita.plugin.reconcile.Proposal
import kalita.plugin.reconcile.ReconciliationReport
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun LocalDate?.fmt(): String = this?.format(DATE_FORMAT) ?: ""

private fun BigDecimal?.fmt(): String = this?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: ""

/**
 * «Плагины → <импортёр> → Импортировать выписку…» end to end (dev_plan.md §11.4, FR-B1…FR-B6):
 * pick the file → the importer plugin reads it → missing account/currency mappings are asked once
 * (FR-B3) → the reconciler's proposals are shown per (account, currency) group → the user's
 * decisions are applied as one change set, with links remembered for the next import.
 */
internal fun runStatementImport(parent: QWidget, controller: AppController, importerId: String, extensions: Set<String>) {
    val settings = controller.state.value as? AppState.Ready ?: run {
        QMessageBox.information(parent, Strings.IMPORT_TITLE, "Сначала откройте базу.")
        return
    }
    val filter = extensions.joinToString(" ") { "*.$it" }
    val picked = QFileDialog.getOpenFileName(parent, Strings.IMPORT_OPEN_TITLE, System.getProperty("user.home"), "Выписка ($filter);;Все файлы (*)").result
    if (picked.isNullOrEmpty()) return
    val transactions = controller.readStatement(importerId, File(picked)).getOrElse { e ->
        QMessageBox.critical(parent, Strings.IMPORT_TITLE, "Не удалось прочитать выписку:\n${e.message ?: e}")
        return
    }
    val mappings = controller.bankMappings() ?: return
    if (!ensureMappings(parent, settings.dataset, mappings, transactions)) return

    val groups = transactions.groupBy { it.bankAccount to it.currency.uppercase() }
    for ((key, group) in groups) {
        val accountId = mappings.accountFor(key.first) ?: continue
        val currencyId = mappings.currencyFor(key.second) ?: continue
        val report = controller.reconcile(group, accountId, currencyId)
        val dataset = (controller.state.value as? AppState.Ready)?.dataset ?: return
        val accountName = dataset.account(accountId)?.name ?: key.first
        if (report.proposals.isEmpty() && report.notInBank.isEmpty()) {
            QMessageBox.information(parent, Strings.IMPORT_TITLE, "$accountName, ${key.second}: ${Strings.IMPORT_NOTHING}\n" + Strings.importSummary(null, group.size - report.skippedPending, report.skippedAlreadyLinked, report.skippedPending))
            continue
        }
        val dialog = StatementImportDialog(parent, dataset, report, accountId, "$accountName · ${key.second}")
        if (dialog.exec() != QDialog.DialogCode.Accepted.value()) continue
        val decisions = dialog.decisions()
        when (val result = controller.applyImport(decisions, accountId)) {
            is SubmitResult.Applied -> QMessageBox.information(
                parent, Strings.IMPORT_TITLE,
                Strings.importApplied(
                    created = decisions.count { it is Decision.Create },
                    moved = decisions.count { it is Decision.MoveToAccount },
                    linked = decisions.count { it is Decision.Link },
                    ignored = decisions.count { it is Decision.Ignore },
                ),
            )
            is SubmitResult.Conflicts -> QMessageBox.warning(parent, Strings.IMPORT_TITLE, "Данные изменились во время импорта (${result.conflicts.size}). Повторите импорт.")
            is SubmitResult.Failed -> QMessageBox.critical(parent, Strings.IMPORT_TITLE, result.reason)
        }
    }
}

/** FR-B3: asks for every bank account / currency of [transactions] that has no Kalita counterpart yet; false if the user cancelled. */
private fun ensureMappings(parent: QWidget, dataset: Dataset, store: BankMappingStore, transactions: List<BankTransaction>): Boolean {
    val accounts = transactions.map { it.bankAccount }.distinct().filter { store.accountFor(it) == null }
    val currencies = transactions.map { it.currency.uppercase() }.distinct().filter { store.currencyFor(it) == null }
    if (accounts.isEmpty() && currencies.isEmpty()) return true
    val dialog = BankMappingDialog(parent, dataset, store, accounts, currencies, requireAll = true)
    return dialog.exec() == QDialog.DialogCode.Accepted.value()
}

/**
 * «Соответствие счетов и валют…» — the FR-B3 table, both as the first-import prompt (only the
 * missing keys, all required) and as the standalone editor of everything remembered so far.
 */
class BankMappingDialog(
    parent: QWidget?,
    dataset: Dataset,
    private val store: BankMappingStore,
    accountKeys: List<String>,
    currencyKeys: List<String>,
    private val requireAll: Boolean,
) : QDialog(parent) {
    private val accounts = dataset.accounts.filter { !it.deleted }
    private val currencies = dataset.currencies.filter { !it.deleted }
    private val accountCombos = LinkedHashMap<String, QComboBox>()
    private val currencyCombos = LinkedHashMap<String, QComboBox>()

    init {
        setWindowTitle(Strings.BANK_MAPPINGS_TITLE)
        setMinimumWidth(520)
        val layout = QFormLayout(this)
        if (requireAll) layout.addRow(hint(Strings.BANK_MAPPING_REQUIRED))
        val existing = store.read()
        if (accountKeys.isEmpty() && currencyKeys.isEmpty()) layout.addRow(hint(Strings.NO_MAPPINGS))
        if (accountKeys.isNotEmpty()) layout.addRow(hint(Strings.BANK_MAPPING_ACCOUNTS))
        for (key in accountKeys) {
            val combo = QComboBox()
            combo.addItem("—")
            accounts.forEach { combo.addItem(it.name ?: "id:${it.id}") }
            existing.accounts[key]?.let { id -> accounts.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { combo.setCurrentIndex(it + 1) } }
            layout.addRow(key, combo)
            accountCombos[key] = combo
        }
        if (currencyKeys.isNotEmpty()) layout.addRow(hint(Strings.BANK_MAPPING_CURRENCIES))
        for (key in currencyKeys) {
            val combo = QComboBox()
            combo.addItem("—")
            currencies.forEach { combo.addItem(it.name ?: "id:${it.id}") }
            val remembered = existing.currencies[key] ?: currencies.firstOrNull { (it.name ?: "").equals(key, ignoreCase = true) }?.id
            remembered?.let { id -> currencies.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { combo.setCurrentIndex(it + 1) } }
            layout.addRow(key, combo)
            currencyCombos[key] = combo
        }
        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel))
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText(Strings.CANCEL)
        buttons.accepted.connect(QMetaObject.Slot0 { onOk() })
        buttons.rejected.connect(this::reject)
        layout.addRow(buttons)
    }

    private fun onOk() {
        if (requireAll && (accountCombos.values.any { it.currentIndex() == 0 } || currencyCombos.values.any { it.currentIndex() == 0 })) {
            QMessageBox.warning(this, Strings.BANK_MAPPINGS_TITLE, Strings.BANK_MAPPING_REQUIRED)
            return
        }
        for ((key, combo) in accountCombos) store.setAccount(key, accounts.getOrNull(combo.currentIndex() - 1)?.id)
        for ((key, combo) in currencyCombos) store.setCurrency(key, currencies.getOrNull(combo.currentIndex() - 1)?.id)
        accept()
    }
}

/**
 * The «Входящие» screen of dev_plan.md §11.4 for one statement: four tabs — exact matches and
 * group-sum matches (accept with a checkbox; only a link is written), new lines (create with
 * a budget item and counterparty of the user's choice, skip, ignore for good, or move the same
 * purchase already entered on another account), and journal rows the bank never showed.
 */
class StatementImportDialog(
    parent: QWidget?,
    private val dataset: Dataset,
    private val report: ReconciliationReport,
    private val accountId: Long,
    subtitle: String,
) : QDialog(parent) {
    private class ExactRow(val proposal: Proposal.Exact, val check: QCheckBox)
    private class SumRow(val proposal: Proposal.Sum, val check: QCheckBox)
    private class NewRow(val proposal: Proposal.New, val action: QComboBox, val item: QComboBox, val items: List<BudgetItem?>, val who: QComboBox)

    private val exactRows = ArrayList<ExactRow>()
    private val sumRows = ArrayList<SumRow>()
    private val newRows = ArrayList<NewRow>()
    private val counterparties = dataset.counterparties.filter { !it.deleted }

    init {
        setWindowTitle("${Strings.IMPORT_TITLE} — $subtitle")
        resize(980, 600)
        val layout = QVBoxLayout(this)
        val period = report.period?.let { "${it.start.fmt()} – ${it.endInclusive.fmt()}" }
        layout.addWidget(hint(Strings.importSummary(period, report.proposals.size + report.skippedAlreadyLinked, report.skippedAlreadyLinked, report.skippedPending)))

        val tabs = QTabWidget()
        tabs.addTab(buildExactTab(), "${Strings.IMPORT_EXACT} (${report.exact.size})")
        tabs.addTab(buildSumTab(), "${Strings.IMPORT_SUMS} (${report.sums.size})")
        tabs.addTab(buildNewTab(), "${Strings.IMPORT_NEW} (${report.new.size})")
        tabs.addTab(buildNotInBankTab(), "${Strings.IMPORT_NOT_IN_BANK} (${report.notInBank.size})")
        tabs.setCurrentIndex(if (report.new.isNotEmpty()) 2 else 0)
        layout.addWidget(tabs, 1)

        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel))
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText(Strings.CANCEL)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText(Strings.APPLY)
        buttons.accepted.connect(QMetaObject.Slot0 { onApply() })
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    /** Same rules as the operation form (FR-21): a line to be created must have a budget item etc. — checked before anything is written. */
    private fun onApply() {
        val problems = invalidCreates(dataset, decisions())
        if (problems.isNotEmpty()) {
            QMessageBox.warning(this, Strings.IMPORT_TITLE, problems.joinToString("\n"))
            return
        }
        accept()
    }

    private fun table(headers: List<String>, rows: Int): QTableWidget {
        val t = QTableWidget(rows, headers.size)
        t.setHorizontalHeaderLabels(headers)
        t.verticalHeader().setVisible(false)
        t.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        t.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        t.horizontalHeader().setStretchLastSection(true)
        return t
    }

    private fun describe(op: Operation): String =
        "${op.date.fmt()} ${op.amount.fmt()} · ${dataset.budgetItem(op.budgetItemId)?.name ?: "—"}" +
            (op.counterparty(dataset)?.let { " · $it" } ?: "") + (op.comment?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

    private fun Operation.counterparty(ds: Dataset): String? = ds.counterparty(counterpartyId)?.name

    private fun buildExactTab(): QWidget {
        val t = table(listOf("", Strings.IMPORT_BANK_DATE, Strings.AMOUNT, Strings.IMPORT_DESCRIPTION, Strings.IMPORT_JOURNAL_ROW), report.exact.size)
        for ((i, p) in report.exact.withIndex()) {
            val check = QCheckBox().also { it.setChecked(true) }
            t.setCellWidget(i, 0, check)
            t.setItem(i, 1, QTableWidgetItem(p.transaction.postedAt.fmt()))
            t.setItem(i, 2, QTableWidgetItem(p.transaction.amount.fmt()))
            t.setItem(i, 3, QTableWidgetItem(p.transaction.description))
            val alt = if (p.alternatives.isEmpty()) "" else "  (+${p.alternatives.size})"
            t.setItem(i, 4, QTableWidgetItem(describe(p.operation) + alt))
            exactRows += ExactRow(p, check)
        }
        t.resizeColumnsToContents()
        return t
    }

    private fun buildSumTab(): QWidget {
        val t = table(listOf("", Strings.IMPORT_BANK_DATE, Strings.AMOUNT, Strings.IMPORT_DESCRIPTION, Strings.IMPORT_JOURNAL_ROWS), report.sums.size)
        for ((i, p) in report.sums.withIndex()) {
            val check = QCheckBox().also { it.setChecked(true) }
            t.setCellWidget(i, 0, check)
            t.setItem(i, 1, QTableWidgetItem(p.transaction.postedAt.fmt()))
            t.setItem(i, 2, QTableWidgetItem(p.transaction.amount.fmt()))
            t.setItem(i, 3, QTableWidgetItem(p.transaction.description))
            t.setItem(i, 4, QTableWidgetItem(p.operations.joinToString("\n") { describe(it) }))
            sumRows += SumRow(p, check)
        }
        t.resizeColumnsToContents()
        t.resizeRowsToContents()
        return t
    }

    private fun buildNewTab(): QWidget {
        val t = table(
            listOf(Strings.IMPORT_ACTION, Strings.IMPORT_BANK_DATE, Strings.AMOUNT, Strings.IMPORT_DESCRIPTION, Strings.BUDGET_ITEM, Strings.COUNTERPARTY, Strings.IMPORT_SIMILAR),
            report.new.size,
        )
        for ((i, p) in report.new.withIndex()) {
            val action = QComboBox()
            action.addItem(Strings.IMPORT_ACTION_CREATE)
            action.addItem(Strings.IMPORT_ACTION_SKIP)
            action.addItem(Strings.IMPORT_ACTION_IGNORE)
            for (op in p.similarElsewhere) {
                val side = if (p.transaction.amount.signum() < 0) op.accountFrom else op.accountTo
                action.addItem(Strings.importActionMove(op.date.fmt(), dataset.account(side)?.name ?: "—"))
            }
            if (p.similarElsewhere.isNotEmpty()) action.setCurrentIndex(3)
            t.setCellWidget(i, 0, action)
            t.setItem(i, 1, QTableWidgetItem(p.transaction.postedAt.fmt()))
            t.setItem(i, 2, QTableWidgetItem(p.transaction.amount.fmt()))
            t.setItem(i, 3, QTableWidgetItem(p.transaction.description))

            val items = budgetItemsFor(p.draft.typeId)
            val itemCombo = QComboBox()
            itemCombo.addItem("—")
            for (it in items.drop(1)) itemCombo.addItem(it!!.second)
            val chosen = items.indexOfFirst { it?.first?.id == p.draft.budgetItemId }
            if (chosen > 0) itemCombo.setCurrentIndex(chosen)
            t.setCellWidget(i, 4, itemCombo)

            val whoCombo = QComboBox()
            whoCombo.addItem("—")
            counterparties.forEach { whoCombo.addItem(it.name ?: "id:${it.id}") }
            val who = counterparties.indexOfFirst { it.id == p.draft.counterpartyId }
            if (who >= 0) whoCombo.setCurrentIndex(who + 1)
            t.setCellWidget(i, 5, whoCombo)

            t.setItem(i, 6, QTableWidgetItem(p.similarElsewhere.joinToString("\n") { describe(it) + " · " + (dataset.account(if (p.transaction.amount.signum() < 0) it.accountFrom else it.accountTo)?.name ?: "") }))
            newRows += NewRow(p, action, itemCombo, items.map { it?.first }, whoCombo)
        }
        t.resizeColumnsToContents()
        t.resizeRowsToContents()
        return t
    }

    /** The budget items under the operation type's root (or all, when the type is unknown), depth-first with indentation, `null` first for «—». */
    private fun budgetItemsFor(typeId: Long?): List<Pair<BudgetItem, String>?> {
        val root = dataset.operationType(typeId)?.budgetRootId
        val children = dataset.budgetItems.filter { !it.deleted }.groupBy { it.parentId }.mapValues { (_, v) -> v.sortedWith(compareBy({ it.ord ?: Long.MAX_VALUE }, { it.id })) }
        val out = ArrayList<Pair<BudgetItem, String>?>()
        out += null
        fun walk(parent: Long?, depth: Int) {
            for (item in children[parent].orEmpty()) {
                out += item to ("    ".repeat(depth) + (item.name ?: "id:${item.id}"))
                walk(item.id, depth + 1)
            }
        }
        if (root != null) walk(root, 0) else walk(null, 0)
        return out
    }

    private fun buildNotInBankTab(): QWidget {
        val w = QWidget()
        val layout = QVBoxLayout(w)
        layout.addWidget(hint(Strings.IMPORT_NOT_IN_BANK_HINT))
        val t = table(listOf(Strings.DATE, Strings.AMOUNT, Strings.BUDGET_ITEM, Strings.COUNTERPARTY, Strings.COMMENT), report.notInBank.size)
        for ((i, op) in report.notInBank.withIndex()) {
            t.setItem(i, 0, QTableWidgetItem(op.date.fmt()))
            t.setItem(i, 1, QTableWidgetItem(op.amount.fmt()))
            t.setItem(i, 2, QTableWidgetItem(dataset.budgetItem(op.budgetItemId)?.name ?: ""))
            t.setItem(i, 3, QTableWidgetItem(op.counterparty(dataset) ?: ""))
            t.setItem(i, 4, QTableWidgetItem(op.comment ?: ""))
        }
        t.resizeColumnsToContents()
        layout.addWidget(t, 1)
        return w
    }

    /** The user's choices, in the shape [AppController.applyImport] takes. */
    fun decisions(): List<Decision> = buildList {
        for (r in exactRows) if (r.check.isChecked) add(Decision.Link(r.proposal, listOf(r.proposal.operation), LinkKind.EXACT))
        for (r in sumRows) if (r.check.isChecked) add(Decision.Link(r.proposal, r.proposal.operations, LinkKind.SUM))
        for (r in newRows) {
            when (val idx = r.action.currentIndex()) {
                0 -> add(
                    Decision.Create(
                        r.proposal,
                        r.proposal.draft.copy(
                            budgetItemId = r.items.getOrNull(r.item.currentIndex())?.id,
                            counterpartyId = counterparties.getOrNull(r.who.currentIndex() - 1)?.id,
                        ),
                    ),
                )
                1 -> Unit
                2 -> add(Decision.Ignore(r.proposal))
                else -> r.proposal.similarElsewhere.getOrNull(idx - 3)?.let { add(Decision.MoveToAccount(r.proposal, it)) }
            }
        }
    }

    /** The mapped account this screen writes to. */
    val targetAccountId: Long get() = accountId

    companion object {
        /** Human-readable validation problems of every [Decision.Create], one line per bad draft. */
        fun invalidCreates(dataset: Dataset, decisions: List<Decision>): List<String> =
            decisions.filterIsInstance<Decision.Create>().mapNotNull { d ->
                val errors = kalita.core.OperationRules(dataset).validate(d.draft)
                if (errors.isEmpty()) null
                else "${d.proposal.transaction.postedAt.fmt()} ${d.proposal.transaction.amount.fmt()} ${d.proposal.transaction.description}: " + errors.joinToString(", ") { Strings.validationError(it) }
            }

        /** Opens the standalone mapping editor over everything remembered for the open database. */
        fun editMappings(parent: QWidget, controller: AppController) {
            val ready = controller.state.value as? AppState.Ready ?: run {
                QMessageBox.information(parent, Strings.BANK_MAPPINGS_TITLE, "Сначала откройте базу.")
                return
            }
            val store = controller.bankMappings() ?: return
            val m = store.read()
            BankMappingDialog(parent, ready.dataset, store, m.accounts.keys.toList(), m.currencies.keys.toList(), requireAll = false).exec()
        }
    }
}
