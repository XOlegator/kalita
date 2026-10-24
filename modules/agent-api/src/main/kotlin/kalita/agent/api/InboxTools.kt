// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.OperationRules
import kalita.storage.api.SubmitResult
import kalita.storage.api.Table
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `list_inbox` (dev_plan.md §12.13, FR-AG4): pending drafts awaiting a human's confirmation. */
class ListInboxTool(private val session: AgentSession) {
    fun execute(): JsonObject {
        val dataset = session.dataset
        val entries = InboxStore(session.dataDirectory).readAll().filter { it.status == INBOX_STATUS_PENDING }
        return buildJsonObject {
            put(
                "entries",
                JsonArray(
                    entries.map { e ->
                        buildJsonObject {
                            put("id", e.id)
                            put("createdAt", e.createdAt.toString())
                            put("device", e.device)
                            put("clientRequestId", e.clientRequestId)
                            put("preview", previewOf(dataset, e.draft))
                        }
                    },
                ),
            )
        }
    }
}

/**
 * `approve_inbox` (dev_plan.md §12.13, FR-AG4): commits one pending draft straight to the database,
 * re-validating it against the *current* dataset first — it may be stale (the person may have
 * deleted the budget item it names since the agent drafted it).
 */
class ApproveInboxTool(private val session: AgentSession) {
    fun execute(inboxId: String): JsonObject {
        val store = InboxStore(session.dataDirectory)
        val entry = store.find(inboxId) ?: return errorResult("no such inbox entry: $inboxId")
        if (entry.status != INBOX_STATUS_PENDING) return errorResult("inbox entry $inboxId is already ${entry.status}")

        val dataset = session.dataset
        val errors = OperationRules(dataset).validate(entry.draft)
        if (errors.isNotEmpty()) {
            return buildJsonObject {
                put("ok", false)
                put("error", "validation_failed")
                put("errors", JsonArray(errors.map { JsonPrimitive(it.name) }))
            }
        }

        val result = session.submit("Агент (${entry.device}): подтверждена операция из «Входящих»", entry.device) { builder ->
            builder.insert(Table.Operations, builder.nextTempKey(), draftToColumns(entry.draft))
        }
        return when (result) {
            is SubmitResult.Applied -> {
                store.updateStatus(inboxId, INBOX_STATUS_APPROVED)
                buildJsonObject {
                    put("ok", true)
                    put("id", inboxId)
                    put("preview", previewOf(dataset, entry.draft))
                }
            }
            is SubmitResult.Conflicts -> errorResult("conflicts: ${result.conflicts.size} field(s) changed since read")
            is SubmitResult.Failed -> errorResult(result.reason)
        }
    }
}

/** `reject_inbox` — not named separately in §12.13's table, but cheap and obviously needed next to
 * [ApproveInboxTool]: leaves a draft's `.mdb` untouched, just marks it rejected so it stops showing
 * up in [ListInboxTool]. */
class RejectInboxTool(private val session: AgentSession) {
    fun execute(inboxId: String): JsonObject {
        val store = InboxStore(session.dataDirectory)
        val entry = store.find(inboxId) ?: return errorResult("no such inbox entry: $inboxId")
        if (entry.status != INBOX_STATUS_PENDING) return errorResult("inbox entry $inboxId is already ${entry.status}")
        store.updateStatus(inboxId, INBOX_STATUS_REJECTED)
        return buildJsonObject {
            put("ok", true)
            put("id", inboxId)
        }
    }
}
