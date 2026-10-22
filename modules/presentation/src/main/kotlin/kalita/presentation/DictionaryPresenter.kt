// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.Account
import kalita.core.BudgetTree
import kalita.core.Counterparty
import kalita.core.Currency
import kalita.core.Dataset
import kalita.core.OperationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DictionaryState(
    val accounts: List<Account> = emptyList(),
    val counterparties: List<Counterparty> = emptyList(),
    val currencies: List<Currency> = emptyList(),
    val operationTypes: List<OperationType> = emptyList(),
    val budgetTree: BudgetTree = BudgetTree(emptyList()),
    val showDeleted: Boolean = false,
)

/**
 * Drives the dictionary views (dev_plan.md §9.8): accounts, counterparties, currencies, operation
 * types, and the budget item tree. Filters soft-deleted items unless [setShowDeleted] is toggled.
 */
class DictionaryPresenter {
    private val _state = MutableStateFlow(DictionaryState())
    val state: StateFlow<DictionaryState> = _state.asStateFlow()

    private var dataset: Dataset = Dataset.EMPTY

    fun load(dataset: Dataset) {
        this.dataset = dataset
        recompute()
    }

    fun setShowDeleted(show: Boolean) {
        _state.value = _state.value.copy(showDeleted = show)
        recompute()
    }

    private fun recompute() {
        val show = _state.value.showDeleted
        _state.value = DictionaryState(
            accounts = if (show) dataset.accounts else dataset.accounts.filter { !it.deleted },
            counterparties = if (show) dataset.counterparties else dataset.counterparties.filter { !it.deleted },
            currencies = if (show) dataset.currencies else dataset.currencies.filter { !it.deleted },
            operationTypes = if (show) dataset.operationTypes else dataset.operationTypes.filter { !it.deleted },
            budgetTree = BudgetTree(if (show) dataset.budgetItems else dataset.budgetItems.filter { !it.deleted }),
            showDeleted = show,
        )
    }
}
