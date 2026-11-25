// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.AccountRules
import kalita.core.Dataset
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.core.OperationRules
import kalita.core.ValidationError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OperationFormState(
    val draft: OperationDraft = OperationDraft(),
    val errors: List<ValidationError> = emptyList(),
    val accountRules: AccountRules = AccountRules(
        fromEnabled = true, fromRequired = false, toEnabled = true, toRequired = false,
    ),
    val dirty: Boolean = false,
) {
    val valid: Boolean get() = errors.isEmpty() && dirty
}

/**
 * Drives the operation form (dev_plan.md §5.1, §9.8). Validates the draft on every change using
 * [OperationRules] and exposes the current account-field rules for the selected operation kind.
 */
class OperationFormPresenter {
    private val _state = MutableStateFlow(OperationFormState())
    val state: StateFlow<OperationFormState> = _state.asStateFlow()

    private var rules: OperationRules = OperationRules(Dataset.EMPTY)
    private var dataset: Dataset = Dataset.EMPTY

    fun load(dataset: Dataset) {
        this.dataset = dataset
        this.rules = OperationRules(dataset)
    }

    /** X-Cash stores the transfer type's own budget root on the journal row. */
    fun transferBudgetItemId(typeId: Long?): Long? =
        dataset.operationType(typeId)?.takeIf { it.kind == OperationKind.Transfer }?.budgetRootId
            ?.takeIf { dataset.budgetItem(it)?.deleted == false }

    fun newOperation(kind: OperationKind? = null) {
        val typeId = kind?.let { k ->
            dataset.operationTypes.firstOrNull { !it.deleted && it.kind == k }?.id
        }
        val defaults = rules.defaults(kind)
        val draft = OperationDraft(
            date = defaults.date,
            currencyId = defaults.currencyId,
            accountFrom = defaults.accountFrom,
            accountTo = defaults.accountTo,
            typeId = typeId,
        )
        updateDraft(draft)
    }

    fun editOperation(draft: OperationDraft) {
        updateDraft(draft)
    }

    fun updateField(transform: OperationDraft.() -> OperationDraft) {
        val previous = _state.value.draft
        val changed = previous.transform()
        val previousTransferItem = transferBudgetItemId(previous.typeId)
        val newDraft = if (
            changed.typeId != previous.typeId && previousTransferItem != null &&
            changed.budgetItemId == previousTransferItem
        ) {
            changed.copy(budgetItemId = null)
        } else {
            changed
        }
        updateDraft(newDraft, dirty = true)
    }

    fun clear() {
        _state.value = OperationFormState()
    }

    private fun updateDraft(draft: OperationDraft, dirty: Boolean = false) {
        val kind = draft.typeId?.let { dataset.operationType(it) }?.kind
        val transferItemId = transferBudgetItemId(draft.typeId)
        val normalized = if (transferItemId != null) draft.copy(budgetItemId = transferItemId) else draft
        _state.value = OperationFormState(
            draft = normalized,
            errors = rules.validate(normalized),
            accountRules = rules.accountRules(kind),
            dirty = dirty,
        )
    }
}
