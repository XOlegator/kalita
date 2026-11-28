// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A potential duplicate; account differences are reported separately for each actual operation. */
data class DuplicateMatch(val operations: List<DuplicateOperation>, val amountDifference: BigDecimal = BigDecimal.ZERO)

data class DuplicateOperation(
    val operation: Operation,
    val accountFromMismatch: Boolean,
    val accountToMismatch: Boolean,
)

/** [truncated] is true when the group search could not look at every combination (see [DuplicateLimits]). */
data class DuplicateSearchResult(val matches: List<DuplicateMatch>, val truncated: Boolean) {
    companion object {
        val EMPTY = DuplicateSearchResult(emptyList(), false)
    }
}

/**
 * Work limits of the group search. They bound the worst case (a day with very many small rows next
 * to a large amount); reaching one makes the result [DuplicateSearchResult.truncated] rather than
 * silently incomplete.
 */
data class DuplicateLimits(
    /** Largest rows of one day tried together; smaller ones are left out when there are more. */
    val candidatesPerDay: Int = 24,
    /** Largest group tried. A receipt rarely has more lines. */
    val maxGroupSize: Int = 12,
    /** Search-tree visits per day. */
    val maxVisitsPerDay: Int = 20_000,
    /** Groups and singles kept in total, best first. */
    val maxMatches: Int = 200,
) {
    init {
        require(candidatesPerDay >= 2 && maxGroupSize >= 2 && maxVisitsPerDay >= 1 && maxMatches >= 1)
    }

    companion object {
        /** The period check runs one search per journal row, so it keeps each one short. */
        val SCAN = DuplicateLimits(candidatesPerDay = 16, maxVisitsPerDay = 2_000, maxMatches = 50)
    }
}

/**
 * Stateless duplicate search: journal rows that look like the same purchase as [draft].
 *
 * A row qualifies when it is live, is not [editingId], has the draft's currency and operation
 * type (or another type of the same kind — expense with expense, income with income — when
 * [kindOf] is given), is dated at most [daysWindow] before or after the draft, and has a positive
 * amount. Missing required draft fields or a non-positive draft amount cannot match.
 *
 * [amountTolerance] widens every comparison: an amount within that many currency units of the
 * target counts (a shop forgiving small change, kopecks lost in conversion), at most
 * [MAX_AMOUNT_TOLERANCE]. Zero compares exactly.
 *
 * Single rows are all compared. A group is two or more rows **of one day** whose amounts add up
 * to the target — the lines of a receipt are entered with one date, the same grouping the bank
 * reconciliation uses (dev_plan.md §11.3 step 3). Days nearest to the draft are searched first,
 * a day's larger rows first; [DuplicateLimits] bound the work and [DuplicateSearchResult.truncated]
 * reports whatever could not be explored. Matches come back best first: smallest amount
 * difference, then fewest rows, then nearest day, then by ID. Each row is identified by its ID;
 * callers must supply unique IDs.
 */
object DuplicateSearch {
    /** The largest [amountTolerance] a caller may ask for, in currency units. */
    val MAX_AMOUNT_TOLERANCE: BigDecimal = BigDecimal(100)

    /** The finest amount difference that counts: Jet's Currency keeps four decimals. */
    private const val SCALE = 4

    fun find(
        draft: OperationDraft,
        operations: Iterable<Operation>,
        editingId: Long? = null,
        daysWindow: Int = 3,
        amountTolerance: BigDecimal = BigDecimal.ZERO,
        kindOf: ((Long?) -> OperationKind?)? = null,
        limits: DuplicateLimits = DuplicateLimits(),
    ): DuplicateSearchResult {
        require(daysWindow >= 0) { "daysWindow must be non-negative" }
        require(amountTolerance.signum() >= 0 && amountTolerance <= MAX_AMOUNT_TOLERANCE) {
            "amountTolerance must be between 0 and $MAX_AMOUNT_TOLERANCE"
        }
        val date = draft.date ?: return DuplicateSearchResult.EMPTY
        val amount = draft.amount?.takeIf { it.signum() > 0 } ?: return DuplicateSearchResult.EMPTY
        val type = draft.typeId ?: return DuplicateSearchResult.EMPTY
        val currency = draft.currencyId ?: return DuplicateSearchResult.EMPTY
        val amountUnits = units(amount) ?: return DuplicateSearchResult.EMPTY
        val tolerance = units(amountTolerance)!!
        val lower = amountUnits - tolerance
        val upper = amountUnits + tolerance
        val kind = kindOf?.invoke(type)
        val first = date.minusDays(daysWindow.toLong())
        val last = date.plusDays(daysWindow.toLong())

        fun sameType(other: Long?) = other == type || kind != null && kindOf?.invoke(other) == kind

        fun match(rows: List<Row>) = rows.sortedBy { it.op.id }.let { sorted ->
            DuplicateMatch(
                sorted.map { DuplicateOperation(it.op, it.op.accountFrom != draft.accountFrom, it.op.accountTo != draft.accountTo) },
                sorted.fold(BigDecimal.ZERO) { sum, row -> sum + row.op.amount!! }.subtract(amount).stripTrailingZeros(),
            )
        }

        val matches = ArrayList<DuplicateMatch>()
        val byDate = HashMap<LocalDate, ArrayList<Row>>()
        for (op in operations) {
            val opDate = op.date ?: continue
            val opAmount = op.amount ?: continue
            if (op.deleted || op.id == editingId || op.currencyId != currency || opAmount.signum() <= 0 ||
                opDate < first || opDate > last || !sameType(op.typeId)
            ) continue
            val opUnits = units(opAmount) ?: continue
            val row = Row(op, opUnits)
            if (opUnits in lower..upper) matches += match(listOf(row))
            // A row at or above the upper bound cannot be part of a larger group.
            if (opUnits < upper) byDate.getOrPut(opDate) { ArrayList() }.add(row)
        }

        var truncated = false
        val days = byDate.keys.sortedBy { abs(ChronoUnit.DAYS.between(date, it)) }
        dayLoop@ for (day in days) {
            val rows = byDate.getValue(day)
            // Flag the omitted rows before any cheap prune — a day that cannot reach the target at
            // all still had rows the limits dropped.
            if (rows.size > limits.candidatesPerDay) truncated = true
            if (rows.size < 2 || rows.fold(0L) { sum, row -> sum + row.units } < lower) continue
            rows.sortWith(compareByDescending<Row> { it.units }.thenBy { it.op.id })
            val candidates = rows.take(limits.candidatesPerDay)
            val suffix = LongArray(candidates.size + 1)
            for (i in candidates.indices.reversed()) suffix[i] = suffix[i + 1] + candidates[i].units
            var visits = 0
            var stopped = false
            val chosen = ArrayList<Row>()
            fun search(start: Int, sum: Long) {
                for (i in start until candidates.size) {
                    val next = sum + candidates[i].units
                    if (next > upper) continue
                    // Even every row from here on cannot reach the target any more.
                    if (sum + suffix[i] < lower) return
                    if (visits >= limits.maxVisitsPerDay || matches.size >= limits.maxMatches) {
                        truncated = true
                        stopped = true
                        return
                    }
                    visits++
                    chosen += candidates[i]
                    if (chosen.size >= 2 && next >= lower) matches += match(chosen)
                    if (chosen.size < limits.maxGroupSize) search(i + 1, next)
                    if (stopped) return
                    chosen.removeAt(chosen.lastIndex)
                }
            }
            search(0, 0)
            if (stopped) break@dayLoop
        }
        // Best first. Every row of a match shares one date, so its first row's distance is the match's.
        matches.sortWith(
            compareBy<DuplicateMatch> { it.amountDifference.abs() }
                .thenBy { it.operations.size }
                .thenBy { abs(ChronoUnit.DAYS.between(date, it.operations.first().operation.date)) }
                .thenBy { it.operations.joinToString("-") { dup -> dup.operation.id.toString() } },
        )
        return DuplicateSearchResult(matches, truncated)
    }

    private class Row(val op: Operation, val units: Long)

    private fun units(value: BigDecimal): Long? = try {
        value.setScale(SCALE, RoundingMode.HALF_UP).movePointRight(SCALE).longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}
