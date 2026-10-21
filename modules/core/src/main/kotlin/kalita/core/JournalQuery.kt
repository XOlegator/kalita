// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
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
        rankInCollation(dataset.allNames())
    }

    // The same for comments: free text, but the dataset is immutable, so collating it once per
    // dataset beats building a collation key per row on every sort.
    private val commentRanks: Map<String, Int> by lazy {
        rankInCollation(dataset.operations.mapNotNullTo(HashSet()) { it.comment })
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

    // Each sort key is extracted once per row — dates as epoch days and names as their rank in
    // collation order, both into a LongArray — so the sort compares primitives wherever it can.
    private fun sorted(rows: List<JournalRow>, sort: List<SortKey>): List<JournalRow> {
        val levels = Array(sort.size) { level(rows, sort[it]) }
        val order = Array(rows.size) { it }
        order.sortWith { a, b ->
            for (level in levels) {
                val c = level.compare(a, b)
                if (c != 0) return@sortWith c
            }
            a.compareTo(b)
        }
        return order.map(rows::get)
    }

    /**
     * Keys of one sort level: [longs] with [EMPTY] for an empty cell, or nullable [decimals]. Decimals
     * are compared by their precomputed `double` first and exactly only when those are equal — the
     * order stays exact, but most comparisons stay primitive.
     */
    private class Level(val longs: LongArray?, val decimals: Array<BigDecimal?>?, val descending: Boolean) {
        private val doubles: DoubleArray? = decimals?.let { d -> DoubleArray(d.size) { d[it]?.toDouble() ?: Double.NaN } }

        fun compare(a: Int, b: Int): Int {
            val c = if (longs != null) longs[a].compareTo(longs[b]) else compareDecimals(a, b)
            return if (descending) -c else c
        }

        private fun compareDecimals(a: Int, b: Int): Int {
            val x = decimals!![a]
            val y = decimals[b]
            return when {
                x == null -> if (y == null) 0 else -1
                y == null -> 1
                else -> doubles!![a].compareTo(doubles[b]).takeIf { it != 0 } ?: x.compareTo(y)
            }
        }
    }

    private fun level(rows: List<JournalRow>, key: SortKey): Level {
        fun longs(extract: (JournalRow) -> Long?) =
            Level(LongArray(rows.size) { extract(rows[it]) ?: EMPTY }, null, key.descending)
        fun decimals(extract: (JournalRow) -> BigDecimal?) =
            Level(null, Array(rows.size) { extract(rows[it]) }, key.descending)
        return when (key.column) {
            JournalColumn.Date -> longs { it.operation.date?.toEpochDay() }
            JournalColumn.Amount -> decimals { it.values.signedAmount }
            JournalColumn.ConvertedAmount -> decimals { it.values.convertedAmount }
            JournalColumn.Rate -> decimals { it.values.rate }
            JournalColumn.Comment -> longs { row -> row.operation.comment?.let { commentRanks.getValue(it).toLong() } }
            JournalColumn.OperationType -> longs { rank(dataset.operationType(it.operation.typeId)?.name) }
            JournalColumn.BudgetItem -> longs { rank(dataset.budgetItem(it.operation.budgetItemId)?.name) }
            JournalColumn.Currency -> longs { rank(dataset.currency(it.operation.currencyId)?.name) }
            JournalColumn.Counterparty -> longs { rank(dataset.counterparty(it.operation.counterpartyId)?.name) }
            JournalColumn.AccountFrom -> longs { rank(dataset.account(it.operation.accountFrom)?.name) }
            JournalColumn.AccountTo -> longs { rank(dataset.account(it.operation.accountTo)?.name) }
        }
    }

    /** `null` — an empty cell — for no reference, an unknown id or a nameless row. */
    private fun rank(name: String?): Long? = name?.let { nameRanks.getValue(it).toLong() }

    private fun Dataset.allNames(): Set<String> = buildSet {
        operationTypes.mapNotNullTo(this) { it.name }
        budgetItems.mapNotNullTo(this) { it.name }
        currencies.mapNotNullTo(this) { it.name }
        counterparties.mapNotNullTo(this) { it.name }
        accounts.mapNotNullTo(this) { it.name }
    }

    // One collation key per string, then a byte-wise sort: much cheaper than Collator.compare
    // inside the sort. A fresh Collator per call, as instances are not thread-safe.
    private fun rankInCollation(strings: Collection<String>): Map<String, Int> {
        val collator = Collator.getInstance(Locale.forLanguageTag("ru"))
        val ranks = HashMap<String, Int>(strings.size * 2)
        strings.map(collator::getCollationKey).sorted().forEachIndexed { i, key -> ranks[key.sourceString] = i }
        return ranks
    }

    private companion object {
        /** An empty cell among long keys: below every epoch day and rank, so it sorts first. */
        const val EMPTY = Long.MIN_VALUE
    }
}
