// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import kalita.core.Operation
import kalita.core.OperationDraft
import kalita.storage.api.ChangeSetBuilder
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.api.toOperationColumns
import java.time.Instant

/** The user's answer to one [Proposal] in the import screen (dev_plan.md §11.4). */
sealed interface Decision {
    val proposal: Proposal

    /** Accept an [Proposal.Exact] or [Proposal.Sum] as proposed — only a link is written, the journal is untouched. */
    data class Link(override val proposal: Proposal, val rows: List<Operation>, val kind: LinkKind) : Decision

    /** Create the (possibly edited) [draft] from a [Proposal.New]. */
    data class Create(override val proposal: Proposal.New, val draft: OperationDraft) : Decision

    /**
     * The purchase was already entered by hand on the wrong account ([Proposal.New.similarElsewhere]):
     * move [operation] to the bank's account instead of creating a duplicate. The only edit of an
     * existing row this screen ever makes — explicit per row, never implied (FR-B4).
     */
    data class MoveToAccount(override val proposal: Proposal.New, val operation: Operation) : Decision

    /** Never ask about this bank line again (a transfer to oneself already counted elsewhere, …). */
    data class Ignore(override val proposal: Proposal) : Decision
}

/**
 * Turns [Decision]s into one change set plus the links to record once it is applied — the link
 * rows of created operations are only known after commit (temp keys → real ids), hence the two
 * steps: [prepare] fills the builder, [Prepared.links] resolves ids from the commit result.
 */
object ReconcileWriter {
    class Prepared internal constructor(
        private val fixed: List<ReconLink>,
        private val pending: List<Pair<String, TempKey>>,
        val insertCount: Int,
        val updateCount: Int,
    ) {
        /** True when the change set has nothing in it — the caller may still record [links] without a commit. */
        val isEmpty: Boolean get() = insertCount == 0 && updateCount == 0

        fun links(tempKeys: Map<TempKey, Long>, at: Instant, device: String): List<ReconLink> =
            fixed.map { it.copy(at = at, device = device) } +
                pending.mapNotNull { (bankTx, key) -> tempKeys[key]?.let { ReconLink(bankTx, listOf(it), LinkKind.CREATED, at, device) } }
    }

    fun prepare(builder: ChangeSetBuilder, decisions: List<Decision>, accountId: Long): Prepared {
        val fixed = ArrayList<ReconLink>()
        val pending = ArrayList<Pair<String, TempKey>>()
        var inserts = 0
        var updates = 0
        val stamp = Instant.EPOCH
        for (d in decisions) {
            val bankTx = d.proposal.transaction.key
            when (d) {
                is Decision.Link -> fixed += ReconLink(bankTx, d.rows.map { it.id }, d.kind, stamp, "")
                is Decision.Ignore -> fixed += ReconLink(bankTx, emptyList(), LinkKind.IGNORED, stamp, "")
                is Decision.Create -> {
                    val key = builder.nextTempKey()
                    builder.insert(Table.Operations, key, d.draft.toOperationColumns())
                    pending += bankTx to key
                    inserts++
                }
                is Decision.MoveToAccount -> {
                    val fromSide = d.proposal.transaction.amount.signum() < 0
                    builder.update(Table.Operations, d.operation.id, mapOf((if (fromSide) "AccountFrom" else "AccountTo") to accountId))
                    fixed += ReconLink(bankTx, listOf(d.operation.id), LinkKind.MANUAL, stamp, "")
                    updates++
                }
            }
        }
        return Prepared(fixed, pending, inserts, updates)
    }
}
