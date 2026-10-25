// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.OperationDraft
import kalita.core.OperationRules
import kalita.storage.api.SubmitResult
import kalita.storage.api.Table
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Guard shared by [UpdateOperationTool]/[DeleteOperationTool]/[RestoreOperationTool]/
 * [AddDictionaryItemTool] (dev_plan.md §12.13's table): unlike `add_operations`, these have no
 * `draft` fallback — §12.14 says editing, deleting, restoring and adding a dictionary item are
 * "only if enabled by the user", i.e. access level `write` alone, never `draft`.
 */
internal fun requireWriteMode(mode: WriteMode) = when (mode) {
    WriteMode.Write -> null
    WriteMode.Draft, WriteMode.ReadOnly -> errorResult("write mode required: this tool has no draft fallback")
}

/**
 * `update_operation` (dev_plan.md §12.13): replaces an existing operation's fields wholesale — same
 * column mapping [AddOperationsTool] uses for insert, applied to an `Op.Update` instead — "only if
 * enabled by the user" (write mode only, §12.14).
 */
class UpdateOperationTool(private val session: AgentSession) {
    fun execute(id: Long, draft: OperationDraft, mode: WriteMode, clientRequestId: String, device: String): JsonObject {
        val idempotency = IdempotencyLog(session.dataDirectory)
        idempotency.find(clientRequestId)?.let { return it }

        requireWriteMode(mode)?.let { return it }

        val dataset = session.dataset
        if (dataset.operations.none { it.id == id }) return errorResult("no such operation: $id")

        val errors = OperationRules(dataset).validate(draft)
        if (errors.isNotEmpty()) {
            return buildJsonObject {
                put("ok", false)
                put("error", "validation_failed")
                put("errors", JsonArray(errors.map { JsonPrimitive(it.name) }))
            }
        }

        val result = session.submit("Агент ($device): изменена операция", device) { builder ->
            builder.update(Table.Operations, id, draftToColumns(draft))
        }
        return when (result) {
            is SubmitResult.Applied -> {
                val json = buildJsonObject {
                    put("ok", true)
                    put("id", id)
                    put("preview", previewOf(dataset, draft))
                }
                idempotency.record(clientRequestId, json)
                json
            }
            is SubmitResult.Conflicts -> errorResult("conflicts: ${result.conflicts.size} field(s) changed since read")
            is SubmitResult.Failed -> errorResult(result.reason)
        }
    }
}

/**
 * `delete_operation` (dev_plan.md §12.13): soft-deletes one operation — "only if enabled by the
 * user" (write mode only, §12.14). Mirrors [kalita.app.desktop.AppController.softDeleteOperations]
 * for one id, not a batch — an agent asked to remove several just calls this once per id.
 */
class DeleteOperationTool(private val session: AgentSession) {
    fun execute(id: Long, mode: WriteMode, clientRequestId: String, device: String): JsonObject {
        val idempotency = IdempotencyLog(session.dataDirectory)
        idempotency.find(clientRequestId)?.let { return it }

        requireWriteMode(mode)?.let { return it }

        val dataset = session.dataset
        if (dataset.operations.none { it.id == id }) return errorResult("no such operation: $id")

        val result = session.submit("Агент ($device): удалена операция", device) { builder -> builder.softDelete(Table.Operations, id) }
        return when (result) {
            is SubmitResult.Applied -> {
                val json = buildJsonObject { put("ok", true); put("id", id) }
                idempotency.record(clientRequestId, json)
                json
            }
            is SubmitResult.Conflicts -> errorResult("conflicts: ${result.conflicts.size} field(s) changed since read")
            is SubmitResult.Failed -> errorResult(result.reason)
        }
    }
}

/**
 * `restore_operation` (dev_plan.md §12.13): the inverse of [DeleteOperationTool] — "only if enabled
 * by the user" (write mode only, §12.14).
 */
class RestoreOperationTool(private val session: AgentSession) {
    fun execute(id: Long, mode: WriteMode, clientRequestId: String, device: String): JsonObject {
        val idempotency = IdempotencyLog(session.dataDirectory)
        idempotency.find(clientRequestId)?.let { return it }

        requireWriteMode(mode)?.let { return it }

        val dataset = session.dataset
        if (dataset.operations.none { it.id == id }) return errorResult("no such operation: $id")

        val result = session.submit("Агент ($device): восстановлена операция", device) { builder -> builder.restore(Table.Operations, id) }
        return when (result) {
            is SubmitResult.Applied -> {
                val json = buildJsonObject { put("ok", true); put("id", id) }
                idempotency.record(clientRequestId, json)
                json
            }
            is SubmitResult.Conflicts -> errorResult("conflicts: ${result.conflicts.size} field(s) changed since read")
            is SubmitResult.Failed -> errorResult(result.reason)
        }
    }
}
