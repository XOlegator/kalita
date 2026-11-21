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
    fun execute(inboxId: String): JsonObject = executeWithHook(inboxId) {}

    internal fun executeWithHook(inboxId: String, afterSubmit: () -> Unit): JsonObject {
        val store = InboxStore(session.dataDirectory)
        return store.locked {
            val entry = find(inboxId) ?: return@locked errorResult("no such inbox entry: $inboxId")
            if (entry.status != INBOX_STATUS_PENDING) return@locked errorResult("inbox entry $inboxId is already ${entry.status}")

            val dataset = session.dataset
            val errors = OperationRules(dataset).validate(entry.draft)
            if (errors.isNotEmpty()) {
                return@locked buildJsonObject {
                    put("ok", false)
                    put("error", "validation_failed")
                    put("errors", JsonArray(errors.map { JsonPrimitive(it.name) }))
                }
            }

            updateStatus(inboxId, INBOX_STATUS_APPLYING)
            val outcome = session.submit("Агент (${entry.device}): подтверждена операция из «Входящих»", entry.device) { builder ->
                builder.insert(Table.Operations, builder.nextTempKey(), draftToColumns(entry.draft))
            }
            afterSubmit()
            when (val result = outcome.result) {
                is SubmitResult.Applied -> {
                    updateStatus(inboxId, INBOX_STATUS_APPROVED)
                    buildJsonObject {
                        put("ok", true)
                        put("id", inboxId)
                        put("changeSetId", outcome.changeSetId.toString())
                        put("preview", previewOf(dataset, entry.draft))
                    }
                }
                is SubmitResult.Conflicts -> errorResult("conflicts: ${result.conflicts.size} field(s) changed since read; inbox recovery required")
                is SubmitResult.Failed -> errorResult("${result.reason}; inbox recovery required")
            }
        }
    }
}

/** `reject_inbox` — not named separately in §12.13's table, but cheap and obviously needed next to
 * [ApproveInboxTool]: leaves a draft's `.mdb` untouched, just marks it rejected so it stops showing
 * up in [ListInboxTool]. */
class RejectInboxTool(private val session: AgentSession) {
    fun execute(inboxId: String): JsonObject {
        val store = InboxStore(session.dataDirectory)
        return store.locked {
            val entry = find(inboxId) ?: return@locked errorResult("no such inbox entry: $inboxId")
            if (entry.status != INBOX_STATUS_PENDING) return@locked errorResult("inbox entry $inboxId is already ${entry.status}")
            updateStatus(inboxId, INBOX_STATUS_REJECTED)
            buildJsonObject {
                put("ok", true)
                put("id", inboxId)
            }
        }
    }
}

/** Human-only recovery of an interrupted or uncertain approval, after inspecting the database. */
class InboxRecovery(private val session: AgentSession) {
    fun list(): JsonObject {
        val dataset = session.dataset
        val entries = InboxStore(session.dataDirectory).readAll().filter { it.status == INBOX_STATUS_APPLYING }
        return buildJsonObject {
            put("entries", JsonArray(entries.map { entry ->
                buildJsonObject {
                    put("id", entry.id)
                    put("createdAt", entry.createdAt.toString())
                    put("device", entry.device)
                    put("clientRequestId", entry.clientRequestId)
                    put("preview", previewOf(dataset, entry.draft))
                }
            }))
        }
    }

    /** Set [committed] only after verifying whether the operation was applied. Never submits a change. */
    fun resolve(inboxId: String, committed: Boolean): JsonObject = InboxStore(session.dataDirectory).locked {
        val entry = find(inboxId) ?: return@locked errorResult("no such inbox entry: $inboxId")
        if (entry.status != INBOX_STATUS_APPLYING) return@locked errorResult("inbox entry $inboxId is ${entry.status}, not applying")
        updateStatus(inboxId, if (committed) INBOX_STATUS_APPROVED else INBOX_STATUS_PENDING)
        buildJsonObject { put("ok", true); put("id", inboxId) }
    }
}
