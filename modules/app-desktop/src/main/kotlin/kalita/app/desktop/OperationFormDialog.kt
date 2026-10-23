// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QDate
import io.qt.core.Qt
import io.qt.widgets.QComboBox
import io.qt.widgets.QDateEdit
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QFormLayout
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QMessageBox
import io.qt.widgets.QPushButton
import io.qt.widgets.QTextEdit
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Dialog for adding, editing, or copying an operation (dev_plan.md §6.4, FR-21).
 * Uses [OperationFormPresenter][kalita.presentation.OperationFormPresenter] for validation.
 *
 * @param editId if non-null, the dialog updates this operation instead of creating a new one.
 */
class OperationFormDialog(
    parent: QWidget?,
    private val controller: AppController,
    title: String,
    private val editId: Long? = null,
) : QDialog(parent) {

    private val typeCombo = QComboBox()
    private val amountField = QLineEdit()
    private val currencyCombo = QComboBox()
    private val dateEdit = QDateEdit()
    private val counterpartyCombo = QComboBox()
    private val accountFromCombo = QComboBox()
    private val accountToCombo = QComboBox()
    private val budgetItemCombo = QComboBox()
    private val commentField = QTextEdit()
    private val errorLabel = QLabel()

    private val types = controller.dictionary.state.value.operationTypes.filter { !it.deleted }
    private val currencies = controller.dictionary.state.value.currencies.filter { !it.deleted }
    private val counterparties = controller.dictionary.state.value.counterparties.filter { !it.deleted }
    private val accounts = controller.dictionary.state.value.accounts.filter { !it.deleted }
    private val budgetItems = controller.dictionary.state.value.budgetTree.preorder()

    private var saved = false

    init {
        setWindowTitle(title)
        resize(500, 450)
        val layout = QFormLayout(this)

        // Operation type
        typeCombo.addItem("—")
        for (t in types) typeCombo.addItem(t.name ?: "id:${t.id}")
        typeCombo.currentIndexChanged.connect(this::onTypeChanged)
        layout.addRow(Strings.OPERATION_TYPE, typeCombo)

        // Amount
        amountField.setPlaceholderText("0,00")
        layout.addRow(Strings.AMOUNT, amountField)

        // Currency
        for (c in currencies) currencyCombo.addItem(c.name ?: "id:${c.id}")
        layout.addRow(Strings.CURRENCY, currencyCombo)

        // Date
        dateEdit.setCalendarPopup(true)
        layout.addRow(Strings.DATE, dateEdit)

        // Counterparty
        counterpartyCombo.addItem("—")
        for (p in counterparties) counterpartyCombo.addItem(p.name ?: "id:${p.id}")
        layout.addRow(Strings.COUNTERPARTY, counterpartyCombo)

        // Account From
        accountFromCombo.addItem("—")
        for (a in accounts) accountFromCombo.addItem(a.name ?: "id:${a.id}")
        layout.addRow(Strings.ACCOUNT_FROM, accountFromCombo)

        // Account To
        accountToCombo.addItem("—")
        for (a in accounts) accountToCombo.addItem(a.name ?: "id:${a.id}")
        layout.addRow(Strings.ACCOUNT_TO, accountToCombo)

        // Budget item
        budgetItemCombo.addItem("—")
        for (item in budgetItems) {
            val tree = controller.dictionary.state.value.budgetTree
            val path = tree.path(item.id).mapNotNull { it.name }.joinToString(" → ")
            budgetItemCombo.addItem(path.ifEmpty { item.name ?: "id:${item.id}" })
        }
        layout.addRow(Strings.BUDGET_ITEM, budgetItemCombo)

        // Comment
        commentField.setMaximumHeight(80)
        layout.addRow(Strings.COMMENT, commentField)

        // Error label
        errorLabel.setStyleSheet("color: red;")
        errorLabel.setWordWrap(true)
        layout.addRow(errorLabel)

        // Buttons: Save, Save and Close, Cancel
        val buttonLayout = QHBoxLayout()
        val saveBtn = QPushButton(Strings.SAVE)
        saveBtn.clicked.connect(this::onSave)
        buttonLayout.addWidget(saveBtn)

        val saveCloseBtn = QPushButton(Strings.SAVE_AND_CLOSE)
        saveCloseBtn.clicked.connect(this::onSaveAndClose)
        buttonLayout.addWidget(saveCloseBtn)

        val cancelBtn = QPushButton(Strings.CANCEL)
        cancelBtn.clicked.connect(this::reject)
        buttonLayout.addWidget(cancelBtn)
        layout.addRow(buttonLayout)

        loadDraftToForm()
    }

    private fun loadDraftToForm() {
        val draft = controller.operationForm.state.value.draft

        // Type
        val typeIdx = draft.typeId?.let { id -> types.indexOfFirst { it.id == id } }
        if (typeIdx != null && typeIdx >= 0) typeCombo.setCurrentIndex(typeIdx + 1)

        // Amount
        draft.amount?.let { amountField.setText(Formatter.amount(it)) }

        // Currency
        val currIdx = draft.currencyId?.let { id -> currencies.indexOfFirst { it.id == id } }
        if (currIdx != null && currIdx >= 0) currencyCombo.setCurrentIndex(currIdx)

        // Date
        val d = draft.date ?: LocalDate.now()
        dateEdit.setDate(QDate(d.year, d.monthValue, d.dayOfMonth))

        // Counterparty
        val cpIdx = draft.counterpartyId?.let { id -> counterparties.indexOfFirst { it.id == id } }
        if (cpIdx != null && cpIdx >= 0) counterpartyCombo.setCurrentIndex(cpIdx + 1)

        // Account From
        val afIdx = draft.accountFrom?.let { id -> accounts.indexOfFirst { it.id == id } }
        if (afIdx != null && afIdx >= 0) accountFromCombo.setCurrentIndex(afIdx + 1)

        // Account To
        val atIdx = draft.accountTo?.let { id -> accounts.indexOfFirst { it.id == id } }
        if (atIdx != null && atIdx >= 0) accountToCombo.setCurrentIndex(atIdx + 1)

        // Budget item
        val biIdx = draft.budgetItemId?.let { id -> budgetItems.indexOfFirst { it.id == id } }
        if (biIdx != null && biIdx >= 0) budgetItemCombo.setCurrentIndex(biIdx + 1)

        // Comment
        if (draft.comment != null) commentField.setPlainText(draft.comment)

        updateAccountFieldsEnabled()
    }

    private fun buildDraft(): OperationDraft {
        val typeIdx = typeCombo.currentIndex() - 1
        val typeId = types.getOrNull(typeIdx)?.id

        val amountText = amountField.text().replace(" ", "").replace(",", ".").trim()
        val amount = amountText.toBigDecimalOrNull()

        val currIdx = currencyCombo.currentIndex()
        val currencyId = currencies.getOrNull(currIdx)?.id

        val qd = dateEdit.date()
        val date = LocalDate.of(qd.year(), qd.month(), qd.day())

        val cpIdx = counterpartyCombo.currentIndex() - 1
        val counterpartyId = counterparties.getOrNull(cpIdx)?.id

        val afIdx = accountFromCombo.currentIndex() - 1
        val accountFrom = accounts.getOrNull(afIdx)?.id

        val atIdx = accountToCombo.currentIndex() - 1
        val accountTo = accounts.getOrNull(atIdx)?.id

        val biIdx = budgetItemCombo.currentIndex() - 1
        val budgetItemId = budgetItems.getOrNull(biIdx)?.id

        val comment = commentField.toPlainText().takeIf { it.isNotBlank() }

        return OperationDraft(
            date = date, amount = amount, currencyId = currencyId,
            budgetItemId = budgetItemId, typeId = typeId, comment = comment,
            counterpartyId = counterpartyId, accountFrom = accountFrom, accountTo = accountTo,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onTypeChanged(index: Int) {
        updateAccountFieldsEnabled()
    }

    private fun updateAccountFieldsEnabled() {
        val typeIdx = typeCombo.currentIndex() - 1
        val kind = types.getOrNull(typeIdx)?.kind
        val rules = kalita.core.OperationRules(
            (controller.state.value as? AppState.Ready)?.dataset ?: kalita.core.Dataset.EMPTY,
        ).accountRules(kind)
        accountFromCombo.setEnabled(rules.fromEnabled)
        accountToCombo.setEnabled(rules.toEnabled)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onSave(checked: Boolean = false) {
        val draft = buildDraft()
        controller.operationForm.updateField { draft }
        val formState = controller.operationForm.state.value
        if (formState.errors.isNotEmpty()) {
            errorLabel.setText(formState.errors.joinToString("\n") { Strings.validationError(it) })
            return
        }
        val result = if (editId != null) {
            controller.updateOperation(editId, draft)
        } else {
            controller.addOperation(draft)
        }
        when (result) {
            is SubmitResult.Applied -> {
                saved = true
                errorLabel.setText("")
                // Stay open for series entry — reset form for a new operation
                controller.operationForm.newOperation(
                    types.getOrNull(typeCombo.currentIndex() - 1)?.kind,
                )
                loadDraftToForm()
            }
            is SubmitResult.Conflicts -> QMessageBox.warning(this, "Конфликт", "Данные изменены извне.")
            is SubmitResult.Failed -> QMessageBox.critical(this, "Ошибка", result.reason)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onSaveAndClose(checked: Boolean = false) {
        val draft = buildDraft()
        controller.operationForm.updateField { draft }
        val formState = controller.operationForm.state.value
        if (formState.errors.isNotEmpty()) {
            errorLabel.setText(formState.errors.joinToString("\n") { Strings.validationError(it) })
            return
        }
        val result = if (editId != null) {
            controller.updateOperation(editId, draft)
        } else {
            controller.addOperation(draft)
        }
        when (result) {
            is SubmitResult.Applied -> {
                saved = true
                accept()
            }
            is SubmitResult.Conflicts -> QMessageBox.warning(this, "Конфликт", "Данные изменены извне.")
            is SubmitResult.Failed -> QMessageBox.critical(this, "Ошибка", result.reason)
        }
    }

    override fun exec(): Int {
        super.exec()
        return if (saved) 1 else 0
    }
}
