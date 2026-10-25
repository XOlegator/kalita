// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import kalita.storage.api.Conflict
import kalita.storage.api.SubmitResult
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Lossless JSON form of a [SubmitResult] — the response half of [AgentChannelServer]/
 * [AgentChannelClient]'s wire protocol (dev_plan.md §12.15, FR-AG6). Cell values inside a
 * [Conflict.FieldChanged] reuse [ChangeSetJson.encodeValue]/[ChangeSetJson.decodeValue], the same
 * tagged form the change set itself travels in.
 */
internal object SubmitResultJson {
    fun encode(result: SubmitResult): JsonObject = buildJsonObject {
        when (result) {
            is SubmitResult.Applied -> {
                put("kind", "applied")
                put("tempKeys", JsonObject(result.tempKeys.entries.associate { (k, v) -> k.value to JsonPrimitive(v) }))
            }
            is SubmitResult.Conflicts -> {
                put("kind", "conflicts")
                put("conflicts", JsonArray(result.conflicts.map(::encodeConflict)))
            }
            is SubmitResult.Failed -> {
                put("kind", "failed")
                put("reason", result.reason)
            }
        }
    }

    fun decode(o: JsonObject): SubmitResult = when (val kind = o.getValue("kind").jsonPrimitive.content) {
        "applied" -> SubmitResult.Applied(
            o.getValue("tempKeys").jsonObject.entries.associate { (k, v) -> TempKey(k) to v.jsonPrimitive.long },
        )
        "conflicts" -> SubmitResult.Conflicts(o.getValue("conflicts").jsonArray.map { decodeConflict(it.jsonObject) })
        "failed" -> SubmitResult.Failed(o.getValue("reason").jsonPrimitive.content)
        else -> throw IllegalArgumentException("unknown SubmitResult kind: $kind")
    }

    private fun encodeConflict(c: Conflict): JsonObject = buildJsonObject {
        put("changeSetId", c.changeSetId.toString())
        put("table", c.table.name)
        when (c) {
            is Conflict.RowMissing -> {
                put("kind", "rowMissing")
                put("id", c.id)
            }
            is Conflict.FieldChanged -> {
                put("kind", "fieldChanged")
                put("id", c.id)
                put("column", c.column)
                put("base", ChangeSetJson.encodeValue(c.base))
                put("actual", ChangeSetJson.encodeValue(c.actual))
            }
            is Conflict.UnresolvedReference -> {
                put("kind", "unresolvedReference")
                put("tempKey", c.tempKey.value)
            }
            is Conflict.UnknownColumn -> {
                put("kind", "unknownColumn")
                put("column", c.column)
            }
        }
    }

    private fun decodeConflict(o: JsonObject): Conflict {
        val changeSetId = UUID.fromString(o.getValue("changeSetId").jsonPrimitive.content)
        val table = Table.valueOf(o.getValue("table").jsonPrimitive.content)
        return when (val kind = o.getValue("kind").jsonPrimitive.content) {
            "rowMissing" -> Conflict.RowMissing(changeSetId, table, o.getValue("id").jsonPrimitive.long)
            "fieldChanged" -> Conflict.FieldChanged(
                changeSetId, table, o.getValue("id").jsonPrimitive.long, o.getValue("column").jsonPrimitive.content,
                ChangeSetJson.decodeValue(o.getValue("base")), ChangeSetJson.decodeValue(o.getValue("actual")),
            )
            "unresolvedReference" -> Conflict.UnresolvedReference(changeSetId, table, TempKey(o.getValue("tempKey").jsonPrimitive.content))
            "unknownColumn" -> Conflict.UnknownColumn(changeSetId, table, o.getValue("column").jsonPrimitive.content)
            else -> throw IllegalArgumentException("unknown Conflict kind: $kind")
        }
    }
}
