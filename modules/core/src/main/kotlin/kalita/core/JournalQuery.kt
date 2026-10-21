// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.text.Collator
import java.time.LocalDate
import java.util.Locale

/** Columns of the income/expense journal grid (dev_plan.md §6.2), in the original's default order. */
enum class JournalColumn {
    Date,
    AccountFrom,

    /** «Сумма», signed (§5.2). */
    Amount,
    ConvertedAmount,
    OperationType,
    BudgetItem,
    Currency,
    Counterparty,
    Comment,
    AccountTo,
    Rate,
}

/** One level of the grid's sort (click, then Shift+click for the next level — FR-25). */
data class SortKey(val column: JournalColumn, val descending: Boolean = false)

/** A visible journal row with its computed columns. */
data class JournalRow(val operation: Operation, val values: JournalValues)

/**
 * The journal's rows for a filter, sort and reduction currency (dev_plan.md §9.7). Build once per
 * [Dataset]; [run] is then cheap enough to call on every change of the filter, sort or reduction
 * currency (NFR-3: 100 000 operations in ≤ 200 ms).
 */
class JournalQuery(private val dataset: Dataset, private val rates: RateService) {
    private val tree = BudgetTree(dataset.budgetItems)

    // Sort key of a reference column: the position of the referenced row's name among all names
    // of the dataset in collation order. Ranked once, on the first sort by such a column.
    private val nameRanks: Map<String, Int> by lazy {
        dataset.allNames().sortedWith(russianCollator()).withIndex().associate { (i, name) -> name to i }
    }

    /**
     * Rows of [filter] sorted by [sort]. Ascending puts empty cells first, descending last; rows
     * equal on every key keep `id` order. Reference and computed columns sort by what the grid
     * shows — names in Russian collation, the signed and converted amounts — not by stored ids
     * (FR-25). Relative dates of the filter resolve against [today].
     */
    fun run(
        filter: JournalFilter,
        sort: List<SortKey> = emptyList(),
        reductionCurrencyId: Long? = rates.baseCurrencyId,
        today: LocalDate = LocalDate.now(),
        showDeleted: Boolean = false,
    ): List<JournalRow> {
        val tests = filter.conditions.map { compile(it, today) }
        val columns = JournalColumns(dataset, rates, reductionCurrencyId)
        val rows = dataset.operations
            .filter { op -> (showDeleted || !op.deleted) && tests.all { it(op) } }
            .sortedBy { it.id }
            .map { JournalRow(it, columns.values(it)) }
        return if (sort.isEmpty()) rows else sorted(rows, sort)
    }

    private fun compile(condition: FilterCondition, today: LocalDate): (Operation) -> Boolean {
        val test: (Operation) -> Boolean? = when (condition) {
            is DateCondition -> { op ->
                op.date?.let { date -> condition.intervals.any { it.contains(date, today) } }
            }
            is AmountCondition -> { op ->
                op.amount?.let { amount ->
                    (condition.min == null || amount >= condition.min) && (condition.max == null || amount <= condition.max)
                }
            }
            is TextCondition -> { op ->
                val text = when (condition.field) {
                    TextField.Comment -> op.comment
                }
                text?.takeIf { it.isNotBlank() }?.contains(condition.substring, ignoreCase = true)
            }
            is RefCondition -> refTest(condition)
        }
        return { op -> condition.inverted != (test(op) ?: condition.includeEmpty) }
    }

    private fun refTest(condition: RefCondition): (Operation) -> Boolean? {
        val ids = if (condition.withDescendants) tree.withDescendants(condition.ids) else condition.ids
        fun matches(id: Long?): Boolean? = id?.let { it in ids }
        return when (condition.field) {
            RefField.OperationType -> { op -> matches(op.typeId) }
            RefField.BudgetItem -> { op -> matches(op.budgetItemId) }
            RefField.Currency -> { op -> matches(op.currencyId) }
            RefField.Counterparty -> { op -> matches(op.counterpartyId) }
            RefField.AccountFrom -> { op -> matches(op.accountFrom) }
            RefField.AccountTo -> { op -> matches(op.accountTo) }
            RefField.Account -> { op ->
                if (op.accountFrom == null && op.accountTo == null) {
                    null
                } else {
                    matches(op.accountFrom) == true || matches(op.accountTo) == true
                }
            }
        }
    }

    // Each sort key is extracted once per row into a comparable value (names become their rank in
    // collation order), so the sort itself only compares precomputed keys.
    private fun sorted(rows: List<JournalRow>, sort: List<SortKey>): List<JournalRow> {
        val collator = russianCollator()
        val keys: List<Array<Comparable<Any>?>> = sort.map { key -> extract(rows, key.column, collator) }
        val order = rows.indices.sortedWith { a, b ->
            for ((level, key) in sort.withIndex()) {
                val c = compareNullsFirst(keys[level][a], keys[level][b])
                if (c != 0) return@sortedWith if (key.descending) -c else c
            }
            a.compareTo(b)
        }
        return order.map(rows::get)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extract(rows: List<JournalRow>, column: JournalColumn, collator: Collator): Array<Comparable<Any>?> {
        val extractor: (JournalRow) -> Comparable<*>? = when (column) {
            JournalColumn.Date -> { row -> row.operation.date }
            JournalColumn.Amount -> { row -> row.values.signedAmount }
            JournalColumn.ConvertedAmount -> { row -> row.values.convertedAmount }
            JournalColumn.Rate -> { row -> row.values.rate }
            JournalColumn.Comment -> { row -> row.operation.comment?.let(collator::getCollationKey) }
            JournalColumn.OperationType -> { row -> rank(dataset.operationType(row.operation.typeId)?.name) }
            JournalColumn.BudgetItem -> { row -> rank(dataset.budgetItem(row.operation.budgetItemId)?.name) }
            JournalColumn.Currency -> { row -> rank(dataset.currency(row.operation.currencyId)?.name) }
            JournalColumn.Counterparty -> { row -> rank(dataset.counterparty(row.operation.counterpartyId)?.name) }
            JournalColumn.AccountFrom -> { row -> rank(dataset.account(row.operation.accountFrom)?.name) }
            JournalColumn.AccountTo -> { row -> rank(dataset.account(row.operation.accountTo)?.name) }
        }
        return Array(rows.size) { extractor(rows[it]) as Comparable<Any>? }
    }

    /** `null` — an empty cell — for no reference, an unknown id or a nameless row. */
    private fun rank(name: String?): Int? = name?.let(nameRanks::getValue)

    private fun Dataset.allNames(): Set<String> = buildSet {
        operationTypes.mapNotNullTo(this) { it.name }
        budgetItems.mapNotNullTo(this) { it.name }
        currencies.mapNotNullTo(this) { it.name }
        counterparties.mapNotNullTo(this) { it.name }
        accounts.mapNotNullTo(this) { it.name }
    }

    private fun compareNullsFirst(a: Comparable<Any>?, b: Comparable<Any>?): Int = when {
        a === b -> 0
        a == null -> -1
        b == null -> 1
        else -> a.compareTo(b)
    }

    // Collator instances are not thread-safe, so every user gets its own.
    private fun russianCollator(): Collator = Collator.getInstance(Locale.forLanguageTag("ru"))
}
