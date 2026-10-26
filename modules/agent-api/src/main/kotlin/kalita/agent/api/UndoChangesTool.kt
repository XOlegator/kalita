// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.SubmitResult
import kalita.storage.local.ChangeHistory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * `undo_changes` (dev_plan.md §12.13-§12.14): reverses one change set — found by [AgentSession]'s
 * own `history.jsonl` ([ChangeHistory], shared with the desktop app, dev_plan.md §9.4) — by
 * building a new [kalita.storage.api.ChangeSet] of the opposite [Op]s and submitting it as an
 * ordinary change, not by erasing the original from history. The undo itself is therefore visible
 * in history too, same as everything else (§FR-7), and can itself be undone.
 *
 * Scoped to the caller's own [device] ("created by this agent", per §12.13's own wording): a
 * change set is only undoable by the exact device string that created it, never a human's edit from
 * the desktop app and never a different agent connection's — whatever this connection's own
 * [WriteMode] allows otherwise. Write mode only, same as [UpdateOperationTool] and friends — undo
 * has no `draft` fallback.
 */
class UndoChangesTool(private val session: AgentSession) {
    fun execute(changeSetId: String, mode: WriteMode, clientRequestId: String, device: String): JsonObject {
        val idempotency = IdempotencyLog(session.dataDirectory)
        idempotency.find(clientRequestId)?.let { return it }

        requireWriteMode(mode)?.let { return it }

        val id = runCatching { UUID.fromString(changeSetId) }.getOrNull()
            ?: return errorResult("invalid changeSetId: $changeSetId")
        val history = ChangeHistory(File(session.dataDirectory, "history.jsonl"))
        val entry = history.read().lastOrNull { it.changeSet.id == id }
            ?: return errorResult("no such change set: $changeSetId")
        if (entry.changeSet.device != device) {
            return errorResult("change set $changeSetId was not created by this connection")
        }
        if (entry.changeSet.ops.isEmpty()) {
            return errorResult("change set $changeSetId has nothing to undo")
        }
        for (op in entry.changeSet.ops) {
            when (op) {
                is Op.Insert -> if (op.tempKey !in entry.tempKeys) {
                    return errorResult("cannot undo $changeSetId: missing applied id for an inserted row")
                }
                is Op.Update -> if (op.row !is RowRef.Existing) {
                    return errorResult("cannot undo $changeSetId: unsupported row reference")
                }
                is Op.SetDeleted -> if (op.row !is RowRef.Existing) {
                    return errorResult("cannot undo $changeSetId: unsupported row reference")
                }
                is Op.HardDelete -> return errorResult("cannot undo $changeSetId: it physically removed a row")
            }
        }

        val outcome = session.submit("Агент ($device): откат «${entry.changeSet.description}»", device) { builder ->
            for (op in entry.changeSet.ops) {
                when (op) {
                    is Op.Insert -> builder.softDelete(op.table, entry.tempKeys.getValue(op.tempKey))
                    is Op.Update -> builder.update(op.table, (op.row as RowRef.Existing).id, op.fields.mapValues { it.value.base })
                    is Op.SetDeleted -> {
                        val rowId = (op.row as RowRef.Existing).id
                        if (op.new) builder.restore(op.table, rowId) else builder.softDelete(op.table, rowId)
                    }
                    is Op.HardDelete -> error("unreachable: checked above")
                }
            }
        }
        return when (val result = outcome.result) {
            is SubmitResult.Applied -> {
                val json = buildJsonObject {
                    put("ok", true)
                    put("undoneChangeSetId", changeSetId)
                    put("changeSetId", outcome.changeSetId.toString())
                }
                idempotency.record(clientRequestId, json)
                json
            }
            is SubmitResult.Conflicts -> errorResult("conflicts: ${result.conflicts.size} field(s) changed since the original change — undo it from the program instead")
            is SubmitResult.Failed -> errorResult(result.reason)
        }
    }
}
