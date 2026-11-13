// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QDate
import io.qt.core.QMetaObject
import io.qt.core.Qt
import io.qt.gui.QPixmap
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QCheckBox
import io.qt.widgets.QComboBox
import io.qt.widgets.QDateEdit
import io.qt.widgets.QDialog
import io.qt.widgets.QFormLayout
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QInputDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QPushButton
import io.qt.widgets.QScrollArea
import io.qt.widgets.QTableWidget
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.agent.api.ReceiptProposal
import kalita.agent.api.ReceiptReviewModel
import kalita.agent.api.budgetPath
import kalita.agent.api.receiptImageAvailable
import kalita.core.Dataset
import kalita.core.OperationKind
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The receipt window (dev_plan.md §12.19): shows the lines of one receipt an agent recognized, with
 * the budget item it picked for each, the running sum against the receipt total and the paying
 * account. The person fixes items, amounts and lines, then saves (one change set, the decision and
 * the corrections go to the learning layer) or rejects. All state and rules live in
 * [ReceiptReviewModel]; this class only binds it to widgets.
 */
class ReceiptReviewDialog(
    parent: QWidget?,
    private val controller: AppController,
    private val proposal: ReceiptProposal,
) : QDialog(parent) {
    enum class Outcome { Saved, Rejected, Later }

    var outcome: Outcome = Outcome.Later
        private set

    private val dataset: Dataset = controller.currentDatabase() ?: Dataset.EMPTY
    private val model = ReceiptReviewModel(proposal, dataset)

    private val merchantField = QLineEdit()
    private val dateEdit = QDateEdit()
    private val accountCombo = QComboBox()
    private val currencyCombo = QComboBox()
    private val mergeCheck = QCheckBox(Strings.RECEIPT_MERGE)
    private val table = QTableWidget(0, 4)
    private val totalLabel = QLabel()
    private val problemsLabel = QLabel()
    private val saveButton = QPushButton(Strings.RECEIPT_SAVE)

    private val accounts = dataset.accounts.filter { !it.deleted }
    private val currencies = dataset.currencies.filter { !it.deleted }

    /** Budget items offered in the combos: the expense branch (or everything when the type has no branch), labelled by full path. */
    private val itemChoices: List<Pair<Long, String>> = run {
        val rootId = dataset.operationTypes.firstOrNull { !it.deleted && it.kind == OperationKind.Expense }?.budgetRootId
        fun inBranch(id: Long): Boolean {
            if (rootId == null) return true
            var cur = dataset.budgetItem(id)
            val seen = HashSet<Long>()
            while (cur != null && seen.add(cur.id)) {
                if (cur.id == rootId) return true
                cur = dataset.budgetItem(cur.parentId)
            }
            return false
        }
        dataset.budgetItems.filter { !it.deleted && inBranch(it.id) }
            .mapNotNull { item -> budgetPath(dataset, item.id)?.let { item.id to it } }
            .sortedBy { it.second }
    }

    private var rebuilding = false

    init {
        setWindowTitle(Strings.RECEIPT_TITLE + (proposal.merchant?.let { " — $it" } ?: ""))
        resize(900, 560)
        val outer = QVBoxLayout(this)

        proposal.note?.takeIf { it.isNotBlank() }?.let { note ->
            val noteLabel = QLabel("Агент: $note")
            noteLabel.setWordWrap(true)
            outer.addWidget(noteLabel)
        }

        val form = QFormLayout()
        merchantField.setText(model.merchant.orEmpty())
        merchantField.textChanged.connect(QMetaObject.Slot1<String> { model.merchant = it })
        form.addRow(Strings.RECEIPT_MERCHANT, merchantField)

        dateEdit.setCalendarPopup(true)
        val d = model.date ?: LocalDate.now()
        dateEdit.setDate(QDate(d.year, d.monthValue, d.dayOfMonth))
        model.date = d
        dateEdit.dateChanged.connect(QMetaObject.Slot1<QDate> { model.date = LocalDate.of(it.year(), it.month(), it.day()) })
        form.addRow(Strings.RECEIPT_DATE, dateEdit)

        accountCombo.addItem("—")
        for (a in accounts) accountCombo.addItem(a.name ?: "id:${a.id}")
        accounts.indexOfFirst { it.id == model.accountFromId }.takeIf { it >= 0 }?.let { accountCombo.setCurrentIndex(it + 1) }
        accountCombo.currentIndexChanged.connect(QMetaObject.Slot1<Int> { model.accountFromId = accounts.getOrNull(it - 1)?.id; refresh() })
        form.addRow(Strings.RECEIPT_ACCOUNT, accountCombo)

        for (c in currencies) currencyCombo.addItem(c.name ?: "id:${c.id}")
        currencies.indexOfFirst { it.id == model.currencyId }.takeIf { it >= 0 }?.let { currencyCombo.setCurrentIndex(it) }
        model.currencyId = currencies.getOrNull(currencyCombo.currentIndex())?.id
        currencyCombo.currentIndexChanged.connect(QMetaObject.Slot1<Int> { model.currencyId = currencies.getOrNull(it)?.id })
        form.addRow(Strings.RECEIPT_CURRENCY, currencyCombo)
        outer.addLayout(form)

        table.setHorizontalHeaderLabels(listOf(Strings.RECEIPT_COL_TEXT, Strings.RECEIPT_COL_AMOUNT, Strings.RECEIPT_COL_ITEM, Strings.RECEIPT_COL_AGENT))
        table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        table.horizontalHeader().setStretchLastSection(true)
        table.setColumnWidth(0, 300)
        table.setColumnWidth(1, 100)
        table.setColumnWidth(2, 280)
        outer.addWidget(table, 1)

        val lineButtons = QHBoxLayout()
        val addLine = QPushButton(Strings.RECEIPT_ADD_LINE)
        addLine.clicked.connect(QMetaObject.Slot1<Boolean> { model.addRow(); rebuildTable() })
        lineButtons.addWidget(addLine)
        val removeLine = QPushButton(Strings.RECEIPT_REMOVE_LINE)
        removeLine.clicked.connect(QMetaObject.Slot1<Boolean> { removeSelectedRow() })
        lineButtons.addWidget(removeLine)
        mergeCheck.setChecked(model.mergeByItem)
        mergeCheck.toggled.connect(QMetaObject.Slot1<Boolean> { model.mergeByItem = it })
        lineButtons.addWidget(mergeCheck, 1)
        outer.addLayout(lineButtons)

        totalLabel.setTextFormat(Qt.TextFormat.RichText)
        outer.addWidget(totalLabel)
        problemsLabel.setStyleSheet("color: #b00020;")
        problemsLabel.setWordWrap(true)
        outer.addWidget(problemsLabel)

        val buttons = QHBoxLayout()
        if (receiptImageAvailable(proposal.imagePath)) {
            val image = QPushButton(Strings.RECEIPT_SHOW_IMAGE)
            image.clicked.connect(QMetaObject.Slot1<Boolean> { showImage() })
            buttons.addWidget(image)
        }
        buttons.addStretch(1)
        val later = QPushButton(Strings.RECEIPT_LATER)
        later.clicked.connect(QMetaObject.Slot1<Boolean> { reject() })
        buttons.addWidget(later)
        val rejectButton = QPushButton(Strings.RECEIPT_REJECT)
        rejectButton.clicked.connect(QMetaObject.Slot1<Boolean> { onReject() })
        buttons.addWidget(rejectButton)
        saveButton.setDefault(true)
        saveButton.clicked.connect(QMetaObject.Slot1<Boolean> { onSave() })
        buttons.addWidget(saveButton)
        outer.addLayout(buttons)

        rebuildTable()
    }

    private fun rebuildTable() {
        rebuilding = true
        table.setRowCount(model.rows.size)
        model.rows.forEachIndexed { i, row ->
            val text = QLineEdit(row.text)
            text.textChanged.connect(QMetaObject.Slot1<String> { row.text = it })
            table.setCellWidget(i, 0, text)

            val amount = QLineEdit(row.amount?.let { Formatter.amount(it) }.orEmpty())
            amount.setAlignment(Qt.AlignmentFlag.AlignRight)
            amount.textChanged.connect(QMetaObject.Slot1<String> { row.amount = parseAmount(it); refresh() })
            table.setCellWidget(i, 1, amount)

            val combo = QComboBox()
            combo.addItem(Strings.RECEIPT_NO_ITEM)
            for ((_, label) in itemChoices) combo.addItem(label)
            itemChoices.indexOfFirst { it.first == row.budgetItemId }.takeIf { it >= 0 }?.let { combo.setCurrentIndex(it + 1) }
            combo.currentIndexChanged.connect(QMetaObject.Slot1<Int> { row.budgetItemId = itemChoices.getOrNull(it - 1)?.first; refresh() })
            table.setCellWidget(i, 2, combo)

            val origin = row.originIndex?.let { proposal.lines.getOrNull(it) }
            val agent = QLabel(if (origin == null) Strings.RECEIPT_AGENT_ADDED else budgetPath(dataset, origin.budgetItemId) ?: "—")
            table.setCellWidget(i, 3, agent)
        }
        rebuilding = false
        refresh()
    }

    private fun parseAmount(s: String): BigDecimal? = s.replace(" ", "").replace(",", ".").trim().toBigDecimalOrNull()

    private fun removeSelectedRow() {
        val index = table.currentRow()
        if (index < 0 || index >= model.rows.size) return
        model.rows.removeAt(index)
        rebuildTable()
    }

    /** Recomputes the sum line, highlights the rows the person changed and enables/disables «Сохранить». */
    private fun refresh() {
        if (rebuilding) return
        val total = model.linesTotal()
        val diff = model.difference()
        val sumText = "Итого по строкам: <b>${Formatter.amount(total)}</b>"
        val receiptText = proposal.total?.let { " &nbsp;·&nbsp; По чеку: <b>${Formatter.amount(it)}</b>" }.orEmpty()
        val diffText = when {
            diff == null -> ""
            diff.signum() == 0 -> " &nbsp;<span style=\"color:#2e7d32;\">✓ сходится</span>"
            else -> " &nbsp;<span style=\"color:#b00020;\">расхождение ${Formatter.amount(diff)}</span>"
        }
        totalLabel.setText(sumText + receiptText + diffText)
        model.rows.forEachIndexed { i, row ->
            val agentLabel = table.cellWidget(i, 3) as? QLabel
            agentLabel?.setStyleSheet(if (model.changedByPerson(row)) "color: #b26a00; font-weight: bold;" else "")
        }
        val problems = model.problems()
        problemsLabel.setText(problems.joinToString("\n"))
        saveButton.setEnabled(problems.isEmpty())
    }

    private fun onSave() {
        when (val result = controller.saveReceipt(model)) {
            is SubmitResult.Applied -> {
                outcome = Outcome.Saved
                accept()
            }
            is SubmitResult.Failed -> problemsLabel.setText(result.reason)
            is SubmitResult.Conflicts -> problemsLabel.setText("База изменилась, повторите: ${result.conflicts.size} конфликт(ов)")
        }
    }

    private fun onReject() {
        val reason = QInputDialog.getText(this, Strings.RECEIPT_REJECT_REASON_TITLE, Strings.RECEIPT_REJECT_REASON, QLineEdit.EchoMode.Normal, "") ?: return
        controller.rejectReceipt(proposal, reason)
        outcome = Outcome.Rejected
        accept()
    }

    private fun showImage() {
        val pixmap = QPixmap(proposal.imagePath)
        if (pixmap.isNull) return
        val viewer = QDialog(this)
        viewer.setWindowTitle(Strings.RECEIPT_SHOW_IMAGE.removeSuffix("…"))
        viewer.resize(700, 800)
        val layout = QVBoxLayout(viewer)
        val label = QLabel()
        label.setPixmap(pixmap)
        val scroll = QScrollArea()
        scroll.setWidget(label)
        layout.addWidget(scroll)
        viewer.exec()
    }
}
