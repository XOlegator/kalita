// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.api

import kalita.core.Account
import kalita.core.BudgetItem
import kalita.core.Counterparty
import kalita.core.Currency
import kalita.core.Dataset
import kalita.core.Operation
import kalita.core.OperationType
import kalita.core.Rate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The domain view of a snapshot (dev_plan.md §9.7): column names of appendix A turned into core's
 * entities. Values are read leniently — any numeric type for ids and amounts, a missing column or
 * NULL as `null` — because the snapshot reflects whatever the original stored.
 */
fun DbSnapshot.toDataset(): Dataset = Dataset(
    operations = rows(Table.Operations).map {
        Operation(
            id = it.id(),
            date = it.date("Date"),
            amount = it.decimal("Summa"),
            currencyId = it.long("Currency_ID"),
            budgetItemId = it.long("GroupBudget_ID"),
            typeId = it.long("TypeOper_ID"),
            comment = it["Comment"] as String?,
            counterpartyId = it.long("People_ID"),
            accountFrom = it.long("AccountFrom"),
            accountTo = it.long("AccountTo"),
            deleted = it.deleted(),
        )
    },
    operationTypes = rows(Table.OperationTypes).map {
        OperationType(it.id(), it.name(), it.long("GroupBudget_ID"), it.long("PlusMinus")?.toInt(), it.deleted())
    },
    budgetItems = rows(Table.BudgetItems).map {
        BudgetItem(it.id(), it.long("Parent_ID"), it.name(), it.long("Ord"), it.deleted())
    },
    accounts = rows(Table.Accounts).map { Account(it.id(), it.name(), it.deleted()) },
    counterparties = rows(Table.People).map { Counterparty(it.id(), it.name(), it.deleted()) },
    currencies = rows(Table.Currencies).map { Currency(it.id(), it.name(), it["CB_ID"] as String?, it.deleted()) },
    rates = rows(Table.Rates).map {
        Rate(it.id(), it.long("Currency_ID"), it.date("Date"), (it["Course"] as Number?)?.toDouble(), it.deleted())
    },
)

private fun Row.id(): Long = (getValue("id") as Number).toLong()

private fun Row.long(column: String): Long? = (this[column] as Number?)?.toLong()

private fun Row.name(): String? = this["Name"] as String?

private fun Row.deleted(): Boolean = this["del"] == true

// Jet stores dates as DateTime; the journal's date has no time part (appendix A).
private fun Row.date(column: String): LocalDate? = (this[column] as LocalDateTime?)?.toLocalDate()

private fun Row.decimal(column: String): BigDecimal? = when (val value = this[column]) {
    null -> null
    is BigDecimal -> value
    is Double, is Float -> BigDecimal.valueOf((value as Number).toDouble())
    else -> BigDecimal((value as Number).toLong())
}
