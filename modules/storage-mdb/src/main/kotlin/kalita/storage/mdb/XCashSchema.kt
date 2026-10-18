// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import kalita.storage.api.Table

/** Expected shape of one application table (dev_plan.md appendix A). */
internal data class TableSpec(
    val table: Table,
    val columns: List<Pair<String, DataType>>,
    /** Secondary indexes as (column, ascending) — the primary key on `id` is implied for every table. */
    val secondaryIndexes: List<Pair<String, Boolean>> = emptyList(),
)

private val AUDIT = listOf(
    "id" to DataType.LONG,
    "Editor" to DataType.LONG,
    "Creator" to DataType.LONG,
    "DateEdit" to DataType.SHORT_DATE_TIME,
    "DateCreate" to DataType.SHORT_DATE_TIME,
    "del" to DataType.BOOLEAN,
)

/** Single source of truth for the MVP tables: drives both [SchemaFingerprint] and the test fixture builder. */
internal val XCASH_SCHEMA: List<TableSpec> = listOf(
    TableSpec(
        Table.Operations,
        AUDIT + listOf(
            "Date" to DataType.SHORT_DATE_TIME,
            "Summa" to DataType.MONEY,
            "Currency_ID" to DataType.LONG,
            "GroupBudget_ID" to DataType.LONG,
            "TypeOper_ID" to DataType.LONG,
            "Comment" to DataType.MEMO,
            "People_ID" to DataType.LONG,
            "AccountFrom" to DataType.LONG,
            "AccountTo" to DataType.LONG,
        ),
    ),
    TableSpec(
        Table.BudgetItems,
        AUDIT + listOf("Parent_ID" to DataType.LONG, "Name" to DataType.TEXT, "Ord" to DataType.LONG),
    ),
    TableSpec(
        Table.OperationTypes,
        AUDIT + listOf("Name" to DataType.TEXT, "GroupBudget_ID" to DataType.LONG, "PlusMinus" to DataType.LONG),
    ),
    TableSpec(Table.Accounts, AUDIT + listOf("Name" to DataType.TEXT)),
    TableSpec(Table.People, AUDIT + listOf("Name" to DataType.TEXT)),
    TableSpec(Table.Currencies, AUDIT + listOf("Name" to DataType.TEXT, "CB_ID" to DataType.TEXT)),
    TableSpec(
        Table.Rates,
        AUDIT + listOf("Currency_ID" to DataType.LONG, "Date" to DataType.SHORT_DATE_TIME, "Course" to DataType.DOUBLE),
        secondaryIndexes = listOf("Currency_ID" to true, "Date" to false),
    ),
)
