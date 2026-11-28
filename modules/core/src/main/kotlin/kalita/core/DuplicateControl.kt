// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.time.LocalDate

/** When the duplicate control runs — the program setting «Контроль дублей». */
enum class DuplicateCheckMode {
    /** Every save is checked first: a receipt, a bank import, the operation form. */
    ON_SAVE,

    /** Nothing is checked until the person presses «Проверить». */
    MANUAL,
}

data class DuplicateCheckOptions(
    val mode: DuplicateCheckMode = DuplicateCheckMode.ON_SAVE,
    /**
     * Amount difference — kopecks, small change a shop or the person forgave — that still counts
     * as the same purchase, in currency units, at most [DuplicateSearch.MAX_AMOUNT_TOLERANCE].
     */
    val amountTolerance: BigDecimal = DEFAULT_AMOUNT_TOLERANCE,
    /** How many days either side of a date a journal row may lie (a bank posts later than the purchase). */
    val daysWindow: Int = DEFAULT_DAYS_WINDOW,
) {
    companion object {
        val DEFAULT_AMOUNT_TOLERANCE: BigDecimal = BigDecimal("5")
        const val DEFAULT_DAYS_WINDOW = 3
        val DEFAULT = DuplicateCheckOptions()
    }
}

/** Where the checked drafts come from — it decides which ways out the check offers. */
enum class DuplicateSource {
    /** A receipt the person is reviewing; it stays a draft until it is decided. */
    RECEIPT,

    /** Lines of a bank statement waiting in the inbox. */
    IMPORT,

    /** The operation form, a new row. */
    MANUAL,

    /** The operation form changing an existing row. */
    EDIT,

    /** An agent's draft from «Входящие». */
    INBOX,
}

/** One checked draft and what to call it in the window («Операция 1», a bank line's description…). */
data class DuplicateCandidate(val label: String, val draft: OperationDraft)

/**
 * New draft(s) against journal rows that look like the same purchase. A match is not proof: the
 * person decides, and more detailed data wins — the old rows are filled in from the new, or the
 * less detailed side gives way to the more detailed one.
 */
data class DuplicateFinding(
    /** Indices into the checked drafts: one, or all of them when the whole is compared. */
    val candidates: List<Int>,
    val rows: List<DuplicateOperation>,
    /** Sum of [rows] minus sum of the drafts. */
    val difference: BigDecimal,
    /** Other journal rows that fit too, less well. */
    val alternatives: List<DuplicateMatch> = emptyList(),
)

data class DuplicateReport(val findings: List<DuplicateFinding>, val truncated: Boolean) {
    /** Nothing to ask about. [truncated] alone is a note for the window, not a reason to interrupt. */
    val clean: Boolean get() = findings.isEmpty()
}

/** A field of a kept journal row that the new drafts know better. [column] is the `DvizhenieDeneg` column. */
enum class EnrichField(val label: String, val column: String) {
    ACCOUNT_FROM("Счёт-Откуда", "AccountFrom"),
    ACCOUNT_TO("Счёт-Куда", "AccountTo"),
    COUNTERPARTY("Кто/Кому", "People_ID"),
    BUDGET_ITEM("Статья", "GroupBudget_ID"),
    COMMENT("Комментарий", "Comment"),
}

/** One field a kept row would take from the new drafts; [suggested] is the likely answer. */
data class EnrichChange(val rowId: Long, val field: EnrichField, val value: Any?, val suggested: Boolean)

/** What the person can decide about one [DuplicateFinding]. */
enum class DuplicateAction {
    /** The old rows are marked deleted and the new drafts written — the new side is more detailed. */
    REPLACE,

    /** The purchase is already in the journal: the old rows stay and take what the new drafts add. */
    KEEP_OLD,

    /** Not the same purchase: the new drafts are written and the old rows are left alone. */
    KEEP_BOTH,

    /** Decide later; a draft that can wait keeps the warning. */
    LATER,

    /** Throw the draft away; nothing is written. */
    DISCARD,
}

/** The person's decisions as one change. */
data class DuplicatePlan(
    /** Indices of the checked drafts to write. */
    val inserts: Set<Int> = emptySet(),
    /** Journal rows to mark deleted. */
    val softDeletes: Set<Long> = emptySet(),
    /** Fields to fill in on journal rows that stay. */
    val updates: Map<Long, Map<EnrichField, Any?>> = emptyMap(),
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && softDeletes.isEmpty() && updates.isEmpty()
}

/**
 * One pass of the duplicate control over new drafts before anything is written (dev_plan.md
 * «Контроль дублей»). The same for a receipt, a bank import and the operation form: every complete
 * draft is compared with the journal by [DuplicateSearch], and so is the total of several drafts —
 * a purchase entered earlier as one bank line then meets the receipt's lines.
 *
 * One question is asked per group of journal rows: the best match of a draft, and of the total,
 * unless the journal rows are already asked about.
 */
object DuplicateControl {
    /** Journal rows besides the best one that a window still mentions. */
    const val MAX_ALTERNATIVES = 5

    fun check(
        dataset: Dataset,
        candidates: List<DuplicateCandidate>,
        source: DuplicateSource,
        editingId: Long? = null,
        options: DuplicateCheckOptions = DuplicateCheckOptions.DEFAULT,
        sumTotal: Boolean = false,
    ): DuplicateReport {
        val kindOf: (Long?) -> OperationKind? = { dataset.operationType(it)?.kind }
        val complete = candidates.indices.filter { complete(candidates[it].draft) }
        val findings = ArrayList<DuplicateFinding>()
        val covered = HashSet<Long>()
        var truncated = false
        fun search(draft: OperationDraft): DuplicateSearchResult {
            val result = DuplicateSearch.find(draft, dataset.operations, editingId, options.daysWindow, options.amountTolerance, kindOf)
            truncated = truncated || result.truncated
            return result
        }
        for (i in complete) {
            val result = search(candidates[i].draft)
            result.matches.firstOrNull()?.let { best ->
                findings += DuplicateFinding(listOf(i), best.operations, best.amountDifference, result.matches.drop(1).take(MAX_ALTERNATIVES))
                covered += best.operations.map { it.operation.id }
            }
        }
        // The total of several drafts — a receipt's lines — against one journal row: the way a
        // purchase entered earlier as a single bank line meets the itemized new entries. Only a
        // group of rows not asked about yet is news.
        if (sumTotal && complete.size > 1) {
            val total = candidates[complete.first()].draft.copy(
                amount = complete.fold(BigDecimal.ZERO) { sum, i -> sum + candidates[i].draft.amount!! },
                budgetItemId = null,
                comment = null,
            )
            search(total).matches.firstOrNull { match -> match.operations.any { it.operation.id !in covered } }?.let { best ->
                findings += DuplicateFinding(complete, best.operations, best.amountDifference)
            }
        }
        return DuplicateReport(findings, truncated)
    }

    private fun complete(draft: OperationDraft) =
        draft.date != null && draft.amount?.signum() == 1 && draft.typeId != null && draft.currencyId != null

    /**
     * Journal rows dated [from]..[to] that look like one purchase entered more than once: one row
     * against another with the same (or near) amount, or one row against a day's rows adding up to
     * it. Rows related to each other are gathered into one [DuplicateGroup]; [DuplicateGroup.suggested]
     * names the less detailed side of each pair.
     */
    fun scan(
        dataset: Dataset,
        from: LocalDate,
        to: LocalDate,
        options: DuplicateCheckOptions = DuplicateCheckOptions.DEFAULT,
    ): DuplicateScan {
        require(!from.isAfter(to)) { "The period ends before it starts" }
        val kindOf: (Long?) -> OperationKind? = { dataset.operationType(it)?.kind }
        val byDate = dataset.operations.filter { !it.deleted && it.date != null }.groupBy { it.date!! }
        val days = byDate.keys.sorted()
        val window = options.daysWindow.toLong()
        val nearbyByDay = days.associateWith { day ->
            days.filter { !it.isBefore(day.minusDays(window)) && !it.isAfter(day.plusDays(window)) }.flatMap(byDate::getValue)
        }
        val links = ArrayList<DuplicateLink>()
        val union = Union()
        var truncated = false
        for (day in days) {
            if (day < from || day > to) continue
            for (row in byDate.getValue(day)) {
                val draft = draftOf(row)
                if (!complete(draft)) continue
                val result = DuplicateSearch.find(draft, nearbyByDay.getValue(day), editingId = row.id, daysWindow = options.daysWindow, amountTolerance = options.amountTolerance, kindOf = kindOf, limits = DuplicateLimits.SCAN)
                truncated = truncated || result.truncated
                for (match in result.matches) {
                    val ids = match.operations.map { it.operation.id }
                    // Two equal rows are reported once, from the lower id; a sum group only from its total.
                    if (ids.size == 1 && ids[0] < row.id) continue
                    links += DuplicateLink(row.id, ids.sorted(), match.amountDifference)
                    ids.forEach { union.join(row.id, it) }
                }
            }
        }
        val byId = dataset.operations.associateBy { it.id }
        val groups = links.groupBy { union.root(it.left) }.map { (_, owned) ->
            val ids = owned.flatMapTo(HashSet()) { listOf(it.left) + it.right }
            val rows = ids.mapNotNull(byId::get).sortedWith(compareBy({ it.date ?: LocalDate.MAX }, { it.id }))
            val keeps = HashSet<Long>()
            val losers = HashSet<Long>()
            for (link in owned) {
                if (link.right.size > 1) {
                    // A single bank-style line against a day's rows: the lines are the detail worth keeping.
                    losers += link.left
                    keeps += link.right
                } else {
                    val w = winner(link, byId)
                    keeps += w
                    losers += setOf(link.left, link.right.single()) - w
                }
            }
            DuplicateGroup(rows, owned, losers.filter { it !in keeps }.toSet())
        }.sortedBy { it.rows.firstOrNull()?.date ?: LocalDate.MAX }
        return DuplicateScan(groups, truncated)
    }

    private fun draftOf(op: Operation) = OperationDraft(
        date = op.date, amount = op.amount, currencyId = op.currencyId, budgetItemId = op.budgetItemId, typeId = op.typeId,
        comment = op.comment, counterpartyId = op.counterpartyId, accountFrom = op.accountFrom, accountTo = op.accountTo,
    )

    /** Filled fields of a row: item, «Кто/Кому», comment, either account. */
    internal fun detail(op: Operation): Int = listOf(op.budgetItemId != null, op.counterpartyId != null, !op.comment.isNullOrBlank(), op.accountFrom != null || op.accountTo != null).count { it }

    /** The row of a one-to-one link that stays: the more detailed one, either when they are alike. */
    private fun winner(link: DuplicateLink, byId: Map<Long, Operation>): Long {
        val right = byId[link.right.single()] ?: return link.left
        val left = byId[link.left] ?: return link.right.single()
        return if (detail(left) < detail(right)) link.right.single() else link.left
    }

    private class Union {
        private val parent = HashMap<Long, Long>()
        fun root(id: Long): Long = parent.getOrPut(id) { id }.let { if (it == id) id else root(it) }
        fun join(a: Long, b: Long) {
            parent[a] = a
            parent[b] = b
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
    }
}

/** One row ([left]) against one or several ([right]) that may be the same purchase. */
data class DuplicateLink(val left: Long, val right: List<Long>, val difference: BigDecimal)

/** Journal rows that may be one purchase entered more than once. */
data class DuplicateGroup(
    val rows: List<Operation>,
    val links: List<DuplicateLink>,
    /** The less detailed side of every [links] — rows to delete first, never a row another link keeps. */
    val suggested: Set<Long>,
)

data class DuplicateScan(val groups: List<DuplicateGroup>, val truncated: Boolean) {
    val clean: Boolean get() = groups.isEmpty()
}

/**
 * What the person decides about one [DuplicateFinding] with [DuplicateAction.KEEP_OLD]: the old
 * rows stay, and these are the fields the new drafts would fill in. Blank fields are always worth
 * filling; a different account is suggested only where the new drafts know it for certain — a
 * bank statement — and merely offered elsewhere.
 */
object DuplicateEnrichment {
    fun changes(finding: DuplicateFinding, candidates: List<DuplicateCandidate>, source: DuplicateSource): List<EnrichChange> {
        val rows = finding.rows.map { it.operation }.sortedWith(compareBy({ it.amount }, { it.id }))
        val drafts = finding.candidates.map { candidates[it].draft }.sortedBy { it.amount }
        // Several new lines cannot enrich one old row without losing their breakdown.
        if (rows.size == 1 && drafts.size > 1) return emptyList()
        val oneByOne = rows.size == drafts.size
        val pairs = if (oneByOne) rows.zip(drafts) else rows.map { it to drafts.first() }
        return pairs.flatMap { (row, draft) ->
            buildList {
                fun account(field: EnrichField, wanted: Long?, current: Long?) {
                    if (wanted != null && current != wanted) add(EnrichChange(row.id, field, wanted, suggested = source == DuplicateSource.IMPORT || current == null))
                }
                account(EnrichField.ACCOUNT_FROM, draft.accountFrom, row.accountFrom)
                account(EnrichField.ACCOUNT_TO, draft.accountTo, row.accountTo)
                if (draft.counterpartyId != null && row.counterpartyId != draft.counterpartyId) {
                    add(EnrichChange(row.id, EnrichField.COUNTERPARTY, draft.counterpartyId, suggested = row.counterpartyId == null))
                }
                // An item belongs to its operation type's subtree, and a comment to one line.
                if (oneByOne) {
                    if (draft.budgetItemId != null && row.budgetItemId != draft.budgetItemId && row.typeId == draft.typeId) {
                        add(EnrichChange(row.id, EnrichField.BUDGET_ITEM, draft.budgetItemId, suggested = row.budgetItemId == null))
                    }
                    if (!draft.comment.isNullOrBlank() && row.comment.isNullOrBlank()) {
                        add(EnrichChange(row.id, EnrichField.COMMENT, draft.comment, suggested = true))
                    }
                }
            }
        }
    }
}

/**
 * The person's answers to a [DuplicateReport], one [DuplicateFinding] at a time, gathered into one
 * [DuplicatePlan]. Shared by every window that asks: [next] hands out the findings still worth a
 * question in order, [actions] says what may be answered (a draft already written by an earlier
 * answer cannot be merged afterwards, a row already kept cannot be replaced), and [decide] records
 * the answer. [DuplicateAction.LATER] and [DuplicateAction.DISCARD] end the pass without a plan.
 */
class DuplicateSession(
    val candidates: List<DuplicateCandidate>,
    val report: DuplicateReport,
    val source: DuplicateSource,
) {
    sealed interface Outcome {
        /** The draft is written; [replaces] are old rows marked deleted in the same change. */
        data class Write(val replaces: Set<Long>) : Outcome

        /** The old rows stay and [changes] fill them in; the draft is not written. */
        data class Merge(val rows: Set<Long>, val changes: List<EnrichChange>) : Outcome
    }

    private class Slot(var outcome: Outcome? = null)

    private val slots = candidates.map { Slot() }.toTypedArray()
    private val replaced = HashSet<Long>()
    private val kept = HashSet<Long>()
    private var position = -1
    private var finished = false

    /** Findings the pass started with. */
    val total: Int = report.findings.size

    /** One-based number of the finding last handed out by [next]. */
    val number: Int get() = position + 1

    /** True once nothing is left to ask, or the pass was ended by LATER or DISCARD. */
    val done: Boolean get() = finished

    /** The next finding that still needs an answer, or `null` when there is none. */
    fun next(): DuplicateFinding? {
        while (!finished && position + 1 < report.findings.size) {
            val finding = report.findings[++position]
            if (actions(finding).isNotEmpty()) return finding
        }
        finished = true
        return null
    }

    fun actions(finding: DuplicateFinding): List<DuplicateAction> {
        val rows = finding.rows.map { it.operation.id }
        val unclaimed = finding.candidates.count { slots[it].outcome == null }
        val whole = rows.size > 1 || rows.size == finding.candidates.size
        return buildList {
            if (rows.none { it in kept }) add(DuplicateAction.REPLACE)
            if (unclaimed > 0 && rows.none { it in replaced } && source != DuplicateSource.EDIT && whole) add(DuplicateAction.KEEP_OLD)
            if (unclaimed > 0 || finding.candidates.any { slots[it].outcome is Outcome.Merge }) add(DuplicateAction.KEEP_BOTH)
            if (source == DuplicateSource.RECEIPT || source == DuplicateSource.IMPORT || source == DuplicateSource.INBOX) {
                add(DuplicateAction.LATER)
                add(DuplicateAction.DISCARD)
            }
        }
    }

    /** Fields [DuplicateAction.KEEP_OLD] could fill in; [suggested] ones are the default selection. */
    fun changes(finding: DuplicateFinding): List<EnrichChange> = DuplicateEnrichment.changes(finding, candidates, source)

    fun decide(finding: DuplicateFinding, action: DuplicateAction, changes: List<EnrichChange> = changes(finding).filter { it.suggested }) {
        val rows = finding.rows.map { it.operation.id }.toSet()
        when (action) {
            DuplicateAction.REPLACE -> {
                replaced += rows
                finding.candidates.forEach { if (slots[it].outcome == null) slots[it].outcome = Outcome.Write(rows) }
            }
            DuplicateAction.KEEP_OLD -> {
                kept += rows
                finding.candidates.forEach { if (slots[it].outcome == null) slots[it].outcome = Outcome.Merge(rows, changes) }
            }
            DuplicateAction.KEEP_BOTH -> finding.candidates.forEach { if (slots[it].outcome == null) slots[it].outcome = Outcome.Write(emptySet()) }
            DuplicateAction.LATER, DuplicateAction.DISCARD -> finished = true
        }
    }

    fun outcome(index: Int): Outcome? = slots[index].outcome

    /**
     * Everything decided so far as one change. A draft no finding claimed is written as usual —
     * only a [DuplicateAction.KEEP_OLD] answer takes its slot out of [DuplicatePlan.inserts].
     */
    fun plan(): DuplicatePlan {
        val updates = LinkedHashMap<Long, MutableMap<EnrichField, Any?>>()
        slots.forEach { slot -> (slot.outcome as? Outcome.Merge)?.changes?.forEach { c -> updates.getOrPut(c.rowId) { LinkedHashMap() }[c.field] = c.value } }
        return DuplicatePlan(
            inserts = slots.indices.filterTo(HashSet()) { slots[it].outcome == null || slots[it].outcome is Outcome.Write },
            softDeletes = replaced.toSet(),
            updates = updates,
        )
    }
}
