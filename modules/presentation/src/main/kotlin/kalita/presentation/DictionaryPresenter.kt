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
    val searchQuery: String = "",
)

/** What kind of dictionary item to count references for. */
enum class DictKind { Account, Counterparty, CurrencyRef, BudgetItem }

/**
 * Drives the dictionary views (dev_plan.md §9.8): accounts, counterparties, currencies, operation
 * types, and the budget item tree. Filters soft-deleted items unless [setShowDeleted] is toggled.
 * Supports text search by name (FR-10).
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

    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        recompute()
    }

    /**
     * Count non-deleted operations referencing the given dictionary item (FR-13).
     * Returns the count for a warning dialog before deletion.
     */
    fun operationReferenceCount(kind: DictKind, id: Long): Int {
        val ops = dataset.operations.filter { !it.deleted }
        return when (kind) {
            DictKind.Account -> ops.count { it.accountFrom == id || it.accountTo == id }
            DictKind.Counterparty -> ops.count { it.counterpartyId == id }
            DictKind.CurrencyRef -> ops.count { it.currencyId == id }
            DictKind.BudgetItem -> {
                val tree = BudgetTree(dataset.budgetItems)
                val descendants = tree.withDescendants(listOf(id))
                ops.count { it.budgetItemId in descendants }
            }
        }
    }

    private fun recompute() {
        val show = _state.value.showDeleted
        val query = _state.value.searchQuery.trim().lowercase()
        _state.value = _state.value.copy(
            accounts = dataset.accounts
                .filter { show || !it.deleted }
                .filter { query.isEmpty() || it.name?.lowercase()?.contains(query) == true },
            counterparties = dataset.counterparties
                .filter { show || !it.deleted }
                .filter { query.isEmpty() || it.name?.lowercase()?.contains(query) == true },
            currencies = dataset.currencies
                .filter { show || !it.deleted }
                .filter { query.isEmpty() || it.name?.lowercase()?.contains(query) == true },
            operationTypes = dataset.operationTypes
                .filter { show || !it.deleted }
                .filter { query.isEmpty() || it.name?.lowercase()?.contains(query) == true },
            budgetTree = BudgetTree(
                dataset.budgetItems
                    .filter { show || !it.deleted }
                    .filter { query.isEmpty() || it.name?.lowercase()?.contains(query) == true },
            ),
        )
    }
}
