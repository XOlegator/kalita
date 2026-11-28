// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A potential duplicate; account differences are reported separately for each actual operation. */
data class DuplicateMatch(val operations: List<DuplicateOperation>)

data class DuplicateOperation(
    val operation: Operation,
    val accountFromMismatch: Boolean,
    val accountToMismatch: Boolean,
)

/** [groupsTruncated] indicates that some eligible rows were not considered for groups. */
data class DuplicateSearchResult(val matches: List<DuplicateMatch>, val groupsTruncated: Boolean)

/**
 * Stateless duplicate search. Dates are compared inclusively against the draft date; type and
 * currency are compared by ID, amounts numerically without rounding. Missing required fields or
 * non-positive amounts cannot match. Both account fields are compared literally, including nulls.
 *
 * Singles are searched across all rows. Groups contain two or three positive-amount rows and use
 * only the first 20 eligible rows smaller than the target, in input order. [DuplicateSearchResult.groupsTruncated]
 * reports when this limit prevents an exhaustive group search. Each row is identified by its ID;
 * callers must supply unique IDs. No matches larger than three rows are attempted.
 */
object DuplicateSearch {
    fun find(
        draft: OperationDraft,
        operations: Iterable<Operation>,
        editingId: Long? = null,
        daysWindow: Int = 3,
    ): DuplicateSearchResult {
        require(daysWindow >= 0) { "daysWindow must be non-negative" }
        val date = draft.date ?: return DuplicateSearchResult(emptyList(), false)
        val amount = draft.amount?.takeIf { it > BigDecimal.ZERO } ?: return DuplicateSearchResult(emptyList(), false)
        val type = draft.typeId ?: return DuplicateSearchResult(emptyList(), false)
        val currency = draft.currencyId ?: return DuplicateSearchResult(emptyList(), false)
        val matches = mutableListOf<DuplicateMatch>()
        val groupCandidates = mutableListOf<Operation>()
        var groupsTruncated = false

        fun match(rows: List<Operation>) = DuplicateMatch(rows.map {
            DuplicateOperation(it, it.accountFrom != draft.accountFrom, it.accountTo != draft.accountTo)
        })

        for (op in operations) {
            val opDate = op.date ?: continue
            val opAmount = op.amount ?: continue
            if (op.deleted || op.id == editingId || op.typeId != type || op.currencyId != currency ||
                abs(ChronoUnit.DAYS.between(date, opDate)) > daysWindow.toLong() || opAmount <= BigDecimal.ZERO
            ) continue
            when {
                opAmount.compareTo(amount) == 0 -> matches.add(match(listOf(op)))
                opAmount < amount -> if (groupCandidates.size < 20) groupCandidates.add(op) else { groupsTruncated = true }
            }
        }
        for (i in groupCandidates.indices) {
            for (j in i + 1 until groupCandidates.size) {
                val sum = groupCandidates[i].amount!!.add(groupCandidates[j].amount!!)
                if (sum.compareTo(amount) == 0) matches.add(match(listOf(groupCandidates[i], groupCandidates[j])))
                for (k in j + 1 until groupCandidates.size) {
                    if (sum.add(groupCandidates[k].amount!!).compareTo(amount) == 0) {
                        matches.add(match(listOf(groupCandidates[i], groupCandidates[j], groupCandidates[k])))
                    }
                }
            }
        }
        return DuplicateSearchResult(matches, groupsTruncated)
    }
}