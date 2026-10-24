// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.Dataset
import kalita.core.JournalColumn
import kalita.core.JournalRow

/**
 * Display text of a journal row's cells (dev_plan.md §6.2), shared by the grid itself and the
 * substring filter (FR-27), which matches against the same text the grid shows — not raw ids.
 */
object JournalRowFormatter {
    fun cellText(dataset: Dataset, row: JournalRow, column: JournalColumn): String {
        val op = row.operation
        val v = row.values
        return when (column) {
            JournalColumn.Date -> op.date?.let { Formatter.date(it) } ?: ""
            JournalColumn.AccountFrom -> dataset.account(op.accountFrom)?.name ?: ""
            JournalColumn.Amount -> v.signedAmount?.let { Formatter.amount(it) } ?: ""
            JournalColumn.ConvertedAmount -> v.convertedAmount?.let { Formatter.amount(it) } ?: ""
            JournalColumn.OperationType -> dataset.operationType(op.typeId)?.name ?: ""
            JournalColumn.BudgetItem -> dataset.budgetItem(op.budgetItemId)?.name ?: ""
            JournalColumn.Currency -> dataset.currency(op.currencyId)?.name ?: ""
            JournalColumn.Counterparty -> dataset.counterparty(op.counterpartyId)?.name ?: ""
            JournalColumn.Comment -> op.comment ?: ""
            JournalColumn.AccountTo -> dataset.account(op.accountTo)?.name ?: ""
            JournalColumn.Rate -> v.rate?.let { Formatter.rate(it) } ?: ""
        }
    }

    /** Every column's [cellText] joined, for a substring search/filter over the whole row. */
    fun allText(dataset: Dataset, row: JournalRow): String =
        JournalColumn.entries.joinToString(" \u0000 ") { cellText(dataset, row, it) }
}
