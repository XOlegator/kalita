// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Validation and default-value rules for the operation form (dev_plan.md §5.1, FR-21).
 *
 * Pure domain logic, shared by both shells: validates a user-entered draft and computes sensible
 * defaults for a new operation (today, last used account, default/base currency).
 */
class OperationRules(private val dataset: Dataset) {
    private val tree = BudgetTree(dataset.budgetItems)

    /** Validate a draft; returns the list of problems (empty = valid). */
    fun validate(draft: OperationDraft): List<ValidationError> = buildList {
        if (draft.date == null) add(ValidationError.MissingDate)
        if (draft.amount == null) add(ValidationError.MissingAmount)
        else if (draft.amount <= BigDecimal.ZERO) add(ValidationError.NonPositiveAmount)
        if (draft.currencyId == null) add(ValidationError.MissingCurrency)
        if (draft.typeId == null) add(ValidationError.MissingOperationType)
        if (draft.budgetItemId == null) add(ValidationError.MissingBudgetItem)

        val kind = draft.typeId?.let { dataset.operationType(it) }?.kind
        val rules = accountRules(kind)
        if (rules.fromRequired && draft.accountFrom == null) add(ValidationError.MissingAccountFrom)
        if (rules.toRequired && draft.accountTo == null) add(ValidationError.MissingAccountTo)

        if (draft.budgetItemId != null && draft.typeId != null) {
            val root = dataset.operationType(draft.typeId)?.budgetRootId
            if (root != null && draft.budgetItemId !in tree.withDescendants(listOf(root))) {
                add(ValidationError.BudgetItemOutsideTypeSubtree)
            }
        }
    }

    /** Which account fields are enabled and required for the given operation kind (§5.1 table). */
    fun accountRules(kind: OperationKind?): AccountRules = when (kind) {
        OperationKind.Income -> AccountRules(fromEnabled = false, fromRequired = false, toEnabled = true, toRequired = true)
        OperationKind.Expense -> AccountRules(fromEnabled = true, fromRequired = true, toEnabled = false, toRequired = false)
        OperationKind.Transfer -> AccountRules(fromEnabled = true, fromRequired = true, toEnabled = true, toRequired = true)
        null -> AccountRules(fromEnabled = true, fromRequired = false, toEnabled = true, toRequired = false)
    }

    /**
     * Sensible defaults for a new operation of the given [kind] (§5.1: today's date, last used
     * account, base/default currency). [kind] is `null` when the user hasn't picked a type yet.
     */
    fun defaults(kind: OperationKind?, today: LocalDate = LocalDate.now()): OperationDefaults {
        val baseCurrency = dataset.detectBaseCurrency()
        val recent = dataset.operations
            .filter { !it.deleted }
            .sortedByDescending { it.date ?: LocalDate.MIN }
        val recentOfKind = kind?.let { k ->
            recent.firstOrNull { dataset.operationType(it.typeId)?.kind == k }
        }
        return OperationDefaults(
            date = today,
            currencyId = baseCurrency ?: dataset.currencies.firstOrNull { !it.deleted }?.id,
            accountFrom = recentOfKind?.accountFrom ?: recent.firstOrNull { it.accountFrom != null }?.accountFrom,
            accountTo = recentOfKind?.accountTo ?: recent.firstOrNull { it.accountTo != null }?.accountTo,
        )
    }
}

/** User-entered operation data before saving (the form's live state, §6.4). */
data class OperationDraft(
    val date: LocalDate? = null,
    val amount: BigDecimal? = null,
    val currencyId: Long? = null,
    val budgetItemId: Long? = null,
    val typeId: Long? = null,
    val comment: String? = null,
    val counterpartyId: Long? = null,
    val accountFrom: Long? = null,
    val accountTo: Long? = null,
)

/** Account field visibility and requirement per operation kind (§5.1). */
data class AccountRules(
    val fromEnabled: Boolean,
    val fromRequired: Boolean,
    val toEnabled: Boolean,
    val toRequired: Boolean,
)

enum class ValidationError {
    MissingDate,
    MissingAmount,
    NonPositiveAmount,
    MissingCurrency,
    MissingBudgetItem,
    MissingOperationType,
    MissingAccountFrom,
    MissingAccountTo,
    BudgetItemOutsideTypeSubtree,
}

/** Default values for a new operation form (§5.1). */
data class OperationDefaults(
    val date: LocalDate,
    val currencyId: Long?,
    val accountFrom: Long?,
    val accountTo: Long?,
)
