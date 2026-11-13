// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import kalita.core.Dataset
import kalita.core.Operation
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.core.RateService
import kalita.plugin.api.BankTransaction
import kalita.plugin.api.Categorizer
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Tunables of dev_plan.md §11.3 step 1. A receipt is usually entered on the day of purchase while
 * the card posts it later, so the window reaches further back than forward: a journal row dated
 * up to [daysBefore] before the bank's value date, or up to [daysAfter] after it, is a candidate.
 */
data class ReconcileConfig(
    val daysBefore: Int = 5,
    val daysAfter: Int = 2,
    /** Largest group of journal rows tried for a [Proposal.Sum] (an itemized receipt rarely has more). */
    val maxGroupSize: Int = 8,
    /** Candidate rows per date considered for a group — subset search is exponential past this. */
    val maxGroupCandidates: Int = 24,
    /**
     * §11.3 step 4: a journal row in another currency than the statement still matches by
     * amount if its value converted at the journal's rates on its date is within this fraction
     * of the bank amount (0.01 = 1 %). Negative disables the cross-currency match.
     */
    val rateTolerance: Double = 0.01,
)

/** What the reconciler proposes for one not-yet-linked bank transaction (dev_plan.md §11.3, §11.4). */
sealed interface Proposal {
    val transaction: BankTransaction

    /** One journal row with the same amount in the window; [alternatives] are the other equal-amount rows further off by date. */
    data class Exact(override val transaction: BankTransaction, val operation: Operation, val alternatives: List<Operation>) : Proposal

    /** Several rows of one day adding up to the bank amount — a receipt split by budget item. */
    data class Sum(override val transaction: BankTransaction, val operations: List<Operation>) : Proposal

    /**
     * Nothing matched on the mapped account: [draft] is the new operation to create (dated by the
     * bank, categorized from history). [similarElsewhere] are unmatched rows with the same
     * amount in the window **on another account** — most likely the same purchase entered with
     * the wrong «Счёт-Откуда» by hand; the user may move one instead of creating a duplicate.
     */
    data class New(override val transaction: BankTransaction, val draft: OperationDraft, val similarElsewhere: List<Operation>) : Proposal
}

data class ReconciliationReport(
    val proposals: List<Proposal>,
    /** Journal rows on the mapped account inside the statement's period that no bank line claimed (§11.3 step 6). */
    val notInBank: List<Operation>,
    val skippedAlreadyLinked: Int,
    val skippedPending: Int,
    val period: ClosedRange<LocalDate>?,
) {
    val exact: List<Proposal.Exact> get() = proposals.filterIsInstance<Proposal.Exact>()
    val sums: List<Proposal.Sum> get() = proposals.filterIsInstance<Proposal.Sum>()
    val new: List<Proposal.New> get() = proposals.filterIsInstance<Proposal.New>()
}

/**
 * dev_plan.md §11.3 over an in-memory [Dataset]. Pure: reads the journal, never writes it (FR-B4);
 * the shell turns the user's decisions into one change set afterwards ([ReconcileWriter]).
 *
 * Greedy in statement order, oldest first: a journal row claimed by one bank line (exact or as
 * part of a group) is not offered to the next one, so two identical bank lines (two coffees)
 * take two different journal rows rather than both pointing at the first.
 */
class Reconciler(
    private val dataset: Dataset,
    private val config: ReconcileConfig = ReconcileConfig(),
    private val categorizer: Categorizer? = null,
) {
    private val rates = RateService.of(dataset)

    suspend fun reconcile(
        transactions: List<BankTransaction>,
        accountId: Long,
        currencyId: Long,
        alreadyLinkedTransactions: Set<String>,
        alreadyLinkedRows: Set<Long>,
    ): ReconciliationReport {
        val posted = transactions.filter { it.status == BankTransaction.Status.Posted }
        val skippedPending = transactions.size - posted.size
        val fresh = posted.filter { it.key !in alreadyLinkedTransactions }.sortedWith(compareBy({ it.postedAt }, { it.externalId }))
        val skippedLinked = posted.size - fresh.size

        val consumed = HashSet<Long>(alreadyLinkedRows)
        val proposals = ArrayList<Proposal>()
        for (tx in fresh) {
            val kind = sideOf(tx)
            val window = window(tx.postedAt)
            val amount = tx.amount.abs()
            val onAccount = candidates(kind, accountId, currencyId, window, consumed)

            val sameCurrency = onAccount.filter { sameAmount(it, amount) }.sortedWith(nearest(tx.postedAt))
            // Same-currency rows always rank above converted ones: an equal amount is a firmer match than a rate guess.
            val exact = sameCurrency + foreignCandidates(kind, accountId, currencyId, window, consumed)
                .filter { matchesByRate(it, currencyId, amount) }.sortedWith(nearest(tx.postedAt))
            if (exact.isNotEmpty()) {
                consumed += exact[0].id
                proposals += Proposal.Exact(tx, exact[0], exact.drop(1))
                continue
            }
            val group = findGroup(onAccount, amount)
            if (group != null) {
                consumed += group.map { it.id }
                proposals += Proposal.Sum(tx, group)
                continue
            }
            val elsewhere = dataset.operations.asSequence()
                .filter { !it.deleted && it.id !in consumed && it.currencyId == currencyId && it.date != null && it.date!! in window }
                .filter { dataset.operationType(it.typeId)?.kind == kind.kind && accountOnSide(it, kind) != accountId }
                .filter { sameAmount(it, amount) }
                .sortedWith(nearest(tx.postedAt))
                .toList()
            proposals += Proposal.New(tx, draftFor(tx, kind, accountId, currencyId), elsewhere)
        }

        val period = posted.takeIf { it.isNotEmpty() }?.let { txs -> txs.minOf { it.postedAt }..txs.maxOf { it.postedAt } }
        val notInBank = if (period == null) {
            emptyList()
        } else {
            val wide = period.start.minusDays(config.daysBefore.toLong())..period.endInclusive.plusDays(config.daysAfter.toLong())
            dataset.operations.asSequence()
                .filter { !it.deleted && it.id !in consumed && it.currencyId == currencyId && it.date != null && it.date!! in wide }
                .filter { it.accountFrom == accountId || it.accountTo == accountId }
                .filter { dataset.operationType(it.typeId)?.kind != null }
                .sortedBy { it.date }
                .toList()
        }
        return ReconciliationReport(proposals, notInBank, skippedLinked, skippedPending, period)
    }

    /** Which journal side a bank line lives on: money out is an expense from the account (or a transfer out of it), money in is income to it. */
    private class Side(val kind: OperationKind, val from: Boolean)

    private fun sideOf(tx: BankTransaction) = if (tx.amount.signum() < 0) Side(OperationKind.Expense, from = true) else Side(OperationKind.Income, from = false)

    private fun accountOnSide(op: Operation, side: Side): Long? = if (side.from) op.accountFrom else op.accountTo

    private fun window(posted: LocalDate) = posted.minusDays(config.daysBefore.toLong())..posted.plusDays(config.daysAfter.toLong())

    private fun candidates(side: Side, accountId: Long, currencyId: Long, window: ClosedRange<LocalDate>, consumed: Set<Long>): List<Operation> =
        dataset.operations.filter { op ->
            !op.deleted && op.id !in consumed && op.currencyId == currencyId && op.amount != null &&
                op.date != null && op.date!! in window &&
                accountOnSide(op, side) == accountId &&
                dataset.operationType(op.typeId)?.kind.let { it == side.kind || it == OperationKind.Transfer }
        }

    /** Rows on the account and side in the window whose currency differs from the statement's (§11.3 step 4). */
    private fun foreignCandidates(side: Side, accountId: Long, currencyId: Long, window: ClosedRange<LocalDate>, consumed: Set<Long>): List<Operation> =
        if (config.rateTolerance < 0) {
            emptyList()
        } else {
            dataset.operations.filter { op ->
                !op.deleted && op.id !in consumed && op.currencyId != null && op.currencyId != currencyId && op.amount != null &&
                    op.date != null && op.date!! in window &&
                    accountOnSide(op, side) == accountId &&
                    dataset.operationType(op.typeId)?.kind.let { it == side.kind || it == OperationKind.Transfer }
            }
        }

    /** The row's amount in the statement currency at the rate of its date is within [ReconcileConfig.rateTolerance] of [amount]. */
    private fun matchesByRate(op: Operation, currencyId: Long, amount: BigDecimal): Boolean {
        val rate = rates.cross(op.currencyId, currencyId, op.date) ?: return false
        val converted = op.amount!!.multiply(rate)
        val allowed = amount.multiply(BigDecimal.valueOf(config.rateTolerance))
        return converted.subtract(amount).abs() <= allowed
    }

    private fun sameAmount(op: Operation, amount: BigDecimal) = op.amount != null && op.amount!!.compareTo(amount) == 0

    private fun nearest(posted: LocalDate): Comparator<Operation> =
        compareBy<Operation> { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, posted)) }.thenBy { it.id }

    /**
     * §11.3 step 3: a same-day group whose amounts add up to the bank amount. Days nearest to the
     * value date first; within a day, the smallest group, preferring rows sharing one «Кто/Кому».
     */
    private fun findGroup(candidates: List<Operation>, amount: BigDecimal): List<Operation>? {
        val byDate = candidates.groupBy { it.date!! }
        val dates = byDate.keys.sortedBy { it }
        var best: List<Operation>? = null
        for (date in dates) {
            val rows = byDate.getValue(date).filter { it.amount!! <= amount }.sortedByDescending { it.amount }.take(config.maxGroupCandidates)
            if (rows.size < 2 || rows.sumOf { it.amount!! } < amount) continue
            val found = subsets(rows, amount)
            if (found.isNotEmpty()) {
                val pick = found.sortedWith(compareBy<List<Operation>> { it.size }.thenBy { it.map { op -> op.counterpartyId }.distinct().size }).first()
                if (best == null || pick.size < best.size) best = pick
                if (best.size == 2) break
            }
        }
        return best
    }

    private fun subsets(rows: List<Operation>, target: BigDecimal): List<List<Operation>> {
        val result = ArrayList<List<Operation>>()
        val chosen = ArrayList<Operation>()
        fun go(start: Int, remaining: BigDecimal) {
            if (remaining.signum() == 0) {
                if (chosen.size >= 2) result += chosen.toList()
                return
            }
            if (chosen.size == config.maxGroupSize || result.size >= 64) return
            for (i in start until rows.size) {
                val a = rows[i].amount!!
                if (a > remaining) continue
                chosen += rows[i]
                go(i + 1, remaining - a)
                chosen.removeAt(chosen.size - 1)
            }
        }
        go(0, target)
        return result
    }

    private suspend fun draftFor(tx: BankTransaction, side: Side, accountId: Long, currencyId: Long): OperationDraft {
        val type = dataset.operationTypes.firstOrNull { !it.deleted && it.kind == side.kind }
        val suggestion = categorizer?.suggest(tx.description, tx.merchant, tx.mcc)?.firstOrNull()
        return OperationDraft(
            date = tx.postedAt,
            amount = tx.amount.abs(),
            currencyId = currencyId,
            budgetItemId = suggestion?.budgetItemId,
            typeId = type?.id,
            comment = tx.description,
            counterpartyId = suggestion?.counterpartyId,
            accountFrom = if (side.from) accountId else null,
            accountTo = if (side.from) null else accountId,
        )
    }
}
