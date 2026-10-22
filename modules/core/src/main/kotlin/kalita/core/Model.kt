// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.time.LocalDate

// Domain entities (dev_plan.md §9.7) over the application tables of appendix A. Every column but
// `id` may be NULL in the file, so everything the original's UI does not guarantee is nullable here:
// the domain reads whatever is stored and leaves validation to the editing path.

/** Sign of an operation type, from `TypeOper.PlusMinus` — never from its id or name (dev_plan.md §4.3). */
enum class OperationKind { Income, Expense, Transfer }

data class OperationType(
    val id: Long,
    val name: String?,
    /** Root of this type's budget item subtree (`TypeOper.GroupBudget_ID`). */
    val budgetRootId: Long?,
    val plusMinus: Int?,
    val deleted: Boolean = false,
) {
    val kind: OperationKind?
        get() = when {
            plusMinus == null -> null
            plusMinus > 0 -> OperationKind.Income
            plusMinus < 0 -> OperationKind.Expense
            else -> OperationKind.Transfer
        }

    /** Factor of the journal's signed amount: transfers count as positive (dev_plan.md §5.2). */
    val sign: Int get() = if (plusMinus == null || plusMinus == 0) 1 else plusMinus.coerceIn(-1, 1)
}

data class BudgetItem(
    val id: Long,
    /** `null` for a root (dev_plan.md §4.1). */
    val parentId: Long?,
    val name: String?,
    val ord: Long?,
    val deleted: Boolean = false,
)

data class Account(val id: Long, val name: String?, val deleted: Boolean = false)

/** «Кто/Кому» (`People`). */
data class Counterparty(val id: Long, val name: String?, val deleted: Boolean = false)

data class Currency(
    val id: Long,
    val name: String?,
    /** Central bank code, e.g. `R01235` (`Currency.CB_ID`). */
    val cbId: String?,
    val deleted: Boolean = false,
)

/** Value of one unit of [currencyId] in the base currency on [date] (`Course`, dev_plan.md §5.5). */
data class Rate(
    val id: Long,
    val currencyId: Long?,
    val date: LocalDate?,
    val value: Double?,
    val deleted: Boolean = false,
)

/** One row of the income/expense journal (`DvizhenieDeneg`). [amount] is stored positive (dev_plan.md §5.1). */
data class Operation(
    val id: Long,
    val date: LocalDate?,
    val amount: BigDecimal?,
    val currencyId: Long?,
    val budgetItemId: Long?,
    val typeId: Long?,
    val comment: String? = null,
    val counterpartyId: Long? = null,
    val accountFrom: Long? = null,
    val accountTo: Long? = null,
    val deleted: Boolean = false,
)

/**
 * Immutable domain view of one database (the application tables, soft-deleted rows included —
 * whether they are shown is up to the caller).
 */
class Dataset(
    val operations: List<Operation> = emptyList(),
    val operationTypes: List<OperationType> = emptyList(),
    val budgetItems: List<BudgetItem> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val counterparties: List<Counterparty> = emptyList(),
    val currencies: List<Currency> = emptyList(),
    val rates: List<Rate> = emptyList(),
) {
    private val typesById = operationTypes.associateBy { it.id }
    private val currenciesById = currencies.associateBy { it.id }
    private val accountsById = accounts.associateBy { it.id }
    private val budgetItemsById = budgetItems.associateBy { it.id }
    private val counterpartiesById = counterparties.associateBy { it.id }

    fun operationType(id: Long?): OperationType? = id?.let(typesById::get)

    fun currency(id: Long?): Currency? = id?.let(currenciesById::get)

    fun account(id: Long?): Account? = id?.let(accountsById::get)

    fun budgetItem(id: Long?): BudgetItem? = id?.let(budgetItemsById::get)

    fun counterparty(id: Long?): Counterparty? = id?.let(counterpartiesById::get)

    /**
     * The base currency is the one rates are quoted in, so it is the only currency without rates of
     * its own (dev_plan.md §4.3 — never a hardcoded id). `null` when that is ambiguous or no
     * currency qualifies; the caller then has to fall back to a setting.
     */
    fun detectBaseCurrency(): Long? {
        val quoted = rates.filter { !it.deleted }.mapNotNullTo(HashSet()) { it.currencyId }
        return currencies.filter { !it.deleted && it.id !in quoted }.singleOrNull()?.id
    }

    companion object {
        val EMPTY = Dataset()
    }
}
