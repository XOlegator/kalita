// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kalita.core.OperationDraft
import kalita.core.OperationRules
import kalita.storage.api.SubmitResult
import kalita.storage.api.Table
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

/**
 * `add_operations` (dev_plan.md §12.13-§12.14): the main write tool. The caller (the CLI today)
 * has already resolved every name to an id via [ResolveTool] — this tool only validates, limits,
 * previews and writes.
 *
 * Order of checks, in the order the plan describes them:
 * 1. Idempotency — a previously-seen [clientRequestId] always short-circuits to its recorded result,
 *    even on `dryRun` (only *recording* a new result is skipped for `dryRun`).
 * 2. [WriteMode.ReadOnly] is rejected immediately — this tool should not have been called at all.
 * 3. Every draft is validated against [OperationRules]; **one** invalid draft fails the whole batch
 *    (returned per-draft-index, nothing written) — "no half-finished batch" is the safer default.
 * 4. [limits] are enforced per draft, not per batch: over [WriteLimits.maxOperationAmount] or
 *    [WriteLimits.maxDailyAmount] (compared against the stored, unconverted `Summa` — simplicity
 *    over cross-currency correctness, documented on [WriteLimits]) rejects *that* draft only, not
 *    the whole call — more useful to an agent than an all-or-nothing failure, since it can retry
 *    just the offending items. The daily sum is grouped by each draft's own date, over the
 *    dataset's existing live operations of that date plus this batch's own running total.
 * 5. `dryRun` returns the preview of every draft (accepted and rejected) without writing anything.
 * 6. [WriteMode.Draft] appends every *accepted* draft to `inbox.jsonl` (never touches the `.mdb`).
 * 7. [WriteMode.Write] submits one [kalita.storage.api.ChangeSet] for every *accepted* draft.
 */
class AddOperationsTool(private val session: AgentSession) {
    fun execute(
        drafts: List<OperationDraft>,
        mode: WriteMode,
        limits: WriteLimits = WriteLimits(),
        dryRun: Boolean = false,
        clientRequestId: String,
        device: String,
        today: LocalDate = LocalDate.now(),
    ): JsonObject {
        val idempotency = IdempotencyLog(session.dataDirectory)
        idempotency.find(clientRequestId)?.let { return it }

        if (mode == WriteMode.ReadOnly) {
            return errorResult("read-only mode: add_operations should not have been called")
        }

        val dataset = session.dataset
        val rules = OperationRules(dataset)
        val errorsByIndex = drafts.map { rules.validate(it) }
        if (errorsByIndex.any { it.isNotEmpty() }) {
            return buildJsonObject {
                put("ok", false)
                put("error", "validation_failed")
                put(
                    "errors",
                    JsonArray(
                        errorsByIndex.withIndex().filter { it.value.isNotEmpty() }.map { (i, errs) ->
                            buildJsonObject {
                                put("index", i)
                                put("errors", JsonArray(errs.map { JsonPrimitive(it.name) }))
                            }
                        },
                    ),
                )
            }
        }

        val decisions = decideLimits(dataset, drafts, limits, today)
        val accepted = decisions.filter { it.accepted }
        val rejected = decisions.filterNot { it.accepted }

        if (dryRun) {
            return buildJsonObject {
                put("ok", true)
                put("mode", mode.name.lowercase())
                put("dryRun", true)
                putDecisions(dataset, accepted, rejected)
            }
        }

        return when (mode) {
            WriteMode.Draft -> applyDraft(dataset, accepted, rejected, device, clientRequestId, idempotency)
            WriteMode.Write -> applyWrite(dataset, accepted, rejected, device, clientRequestId, idempotency)
            WriteMode.ReadOnly -> error("unreachable: checked above")
        }
    }

    private fun applyDraft(
        dataset: Dataset,
        accepted: List<LimitDecision>,
        rejected: List<LimitDecision>,
        device: String,
        clientRequestId: String,
        idempotency: IdempotencyLog,
    ): JsonObject {
        val store = InboxStore(session.dataDirectory)
        val entries = accepted.map { Pair(it.index, store.add(it.draft, device, clientRequestId)) }
        val result = buildJsonObject {
            put("ok", true)
            put("mode", "draft")
            put("dryRun", false)
            putDecisions(dataset, accepted, rejected)
            put("inbox", JsonArray(entries.map { (index, e) -> buildJsonObject { put("index", index); put("id", e.id) } }))
        }
        idempotency.record(clientRequestId, result)
        return result
    }

    private fun applyWrite(
        dataset: Dataset,
        accepted: List<LimitDecision>,
        rejected: List<LimitDecision>,
        device: String,
        clientRequestId: String,
        idempotency: IdempotencyLog,
    ): JsonObject {
        val description = "Агент ($device): добавлено ${accepted.size} операций"
        val result = session.submit(description, device) { builder ->
            for (decision in accepted) builder.insert(Table.Operations, builder.nextTempKey(), draftToColumns(decision.draft))
        }
        return when (result) {
            is SubmitResult.Applied -> {
                val json = buildJsonObject {
                    put("ok", true)
                    put("mode", "write")
                    put("dryRun", false)
                    put("appliedCount", accepted.size)
                    putDecisions(dataset, accepted, rejected)
                }
                idempotency.record(clientRequestId, json)
                json
            }
            is SubmitResult.Conflicts -> errorResult("conflicts: ${result.conflicts.size} field(s) changed since read")
            is SubmitResult.Failed -> errorResult(result.reason)
        }
    }

    private data class LimitDecision(val index: Int, val draft: OperationDraft, val accepted: Boolean, val reason: String?)

    private fun decideLimits(dataset: Dataset, drafts: List<OperationDraft>, limits: WriteLimits, today: LocalDate): List<LimitDecision> {
        val existingByDate = HashMap<LocalDate, BigDecimal>()
        for (op in dataset.operations) {
            if (op.deleted) continue
            val date = op.date ?: continue
            existingByDate.merge(date, op.amount ?: BigDecimal.ZERO, BigDecimal::add)
        }
        val runningByDate = HashMap<LocalDate, BigDecimal>()
        return drafts.mapIndexed { index, draft ->
            val amount = draft.amount ?: BigDecimal.ZERO
            val overOperation = limits.maxOperationAmount?.let { amount > it } == true
            val date = draft.date ?: today
            val soFar = (existingByDate[date] ?: BigDecimal.ZERO) + (runningByDate[date] ?: BigDecimal.ZERO)
            val overDaily = limits.maxDailyAmount?.let { (soFar + amount) > it } == true
            when {
                overOperation -> LimitDecision(index, draft, accepted = false, reason = "exceeds max operation amount")
                overDaily -> LimitDecision(index, draft, accepted = false, reason = "exceeds max daily amount")
                else -> {
                    runningByDate[date] = soFar + amount
                    LimitDecision(index, draft, accepted = true, reason = null)
                }
            }
        }
    }

    private fun JsonObjectBuilder.putDecisions(
        dataset: Dataset,
        accepted: List<LimitDecision>,
        rejected: List<LimitDecision>,
    ) {
        put(
            "accepted",
            JsonArray(
                accepted.map { d ->
                    buildJsonObject {
                        put("index", d.index)
                        put("preview", previewOf(dataset, d.draft))
                    }
                },
            ),
        )
        put(
            "rejected",
            JsonArray(
                rejected.map { d ->
                    buildJsonObject {
                        put("index", d.index)
                        put("preview", previewOf(dataset, d.draft))
                        put("reason", d.reason)
                    }
                },
            ),
        )
    }
}
