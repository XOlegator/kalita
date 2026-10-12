// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import kalita.storage.api.ChangeSet
import kalita.storage.api.FieldChange
import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Lossless JSON form of a [ChangeSet] (history now, the unsent-change queue later — dev_plan.md §9.4).
 * Cell values are tagged `[tag, text]` pairs so Long/Double/Currency/date survive a round trip.
 */
internal object ChangeSetJson {
    fun encode(cs: ChangeSet): JsonObject = buildJsonObject {
        put("id", cs.id.toString())
        put("device", cs.device)
        cs.author?.let { put("author", it) }
        put("createdAt", cs.createdAt.toString())
        put("description", cs.description)
        put("ops", JsonArray(cs.ops.map(::encodeOp)))
    }

    fun decode(o: JsonObject): ChangeSet = ChangeSet(
        id = UUID.fromString(o.str("id")),
        device = o.str("device"),
        author = o["author"]?.jsonPrimitive?.int,
        createdAt = Instant.parse(o.str("createdAt")),
        description = o.str("description"),
        ops = o.getValue("ops").jsonArray.map { decodeOp(it.jsonObject) },
    )

    private fun encodeOp(op: Op): JsonObject = buildJsonObject {
        put("table", op.table.name)
        when (op) {
            is Op.Insert -> {
                put("op", "insert")
                put("tempKey", op.tempKey.value)
                put("values", encodeMap(op.values))
            }
            is Op.Update -> {
                put("op", "update")
                put("row", encodeRef(op.row))
                put(
                    "fields",
                    JsonObject(op.fields.mapValues { (_, c) -> buildJsonArray { add(encodeValue(c.base)); add(encodeValue(c.new)) } }),
                )
            }
            is Op.SetDeleted -> {
                put("op", "setDeleted")
                put("row", encodeRef(op.row))
                put("base", op.base)
                put("new", op.new)
            }
            is Op.HardDelete -> {
                put("op", "hardDelete")
                put("row", encodeRef(op.row))
                put("baseRow", encodeMap(op.baseRow))
            }
        }
    }

    private fun decodeOp(o: JsonObject): Op {
        val table = Table.valueOf(o.str("table"))
        return when (val kind = o.str("op")) {
            "insert" -> Op.Insert(table, TempKey(o.str("tempKey")), decodeMap(o.getValue("values").jsonObject))
            "update" -> Op.Update(
                table,
                decodeRef(o.getValue("row")),
                o.getValue("fields").jsonObject.mapValues { (_, v) ->
                    val pair = v.jsonArray
                    FieldChange(decodeValue(pair[0]), decodeValue(pair[1]))
                },
            )
            "setDeleted" -> Op.SetDeleted(
                table, decodeRef(o.getValue("row")),
                o.getValue("base").jsonPrimitive.boolean, o.getValue("new").jsonPrimitive.boolean,
            )
            "hardDelete" -> Op.HardDelete(table, decodeRef(o.getValue("row")), decodeMap(o.getValue("baseRow").jsonObject))
            else -> throw IllegalArgumentException("unknown op kind: $kind")
        }
    }

    private fun encodeRef(ref: RowRef): JsonElement = when (ref) {
        is RowRef.Existing -> buildJsonArray { add(JsonPrimitive("id")); add(JsonPrimitive(ref.id)) }
        is RowRef.New -> buildJsonArray { add(JsonPrimitive("new")); add(JsonPrimitive(ref.tempKey.value)) }
    }

    private fun decodeRef(e: JsonElement): RowRef {
        val a = e.jsonArray
        return if (a[0].jsonPrimitive.content == "id") RowRef.Existing(a[1].jsonPrimitive.long) else RowRef.New(TempKey(a[1].jsonPrimitive.content))
    }

    private fun encodeMap(m: Map<String, Any?>) = JsonObject(m.mapValues { (_, v) -> encodeValue(v) })

    private fun decodeMap(o: JsonObject): Map<String, Any?> = o.mapValues { (_, v) -> decodeValue(v) }

    fun encodeValue(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Long -> tagged("l", v.toString())
        is Int -> tagged("l", v.toString())
        is Boolean -> tagged("b", v.toString())
        is String -> tagged("s", v)
        is Double -> tagged("d", v.toString())
        is BigDecimal -> tagged("m", v.toPlainString())
        is LocalDateTime -> tagged("t", v.toString())
        is TempKey -> tagged("k", v.value)
        else -> throw IllegalArgumentException("unsupported value type: ${v.javaClass}")
    }

    fun decodeValue(e: JsonElement): Any? {
        if (e is JsonNull) return null
        val a = e.jsonArray
        val text = a[1].jsonPrimitive.content
        return when (val tag = a[0].jsonPrimitive.content) {
            "l" -> text.toLong()
            "b" -> text.toBooleanStrict()
            "s" -> text
            "d" -> text.toDouble()
            "m" -> BigDecimal(text)
            "t" -> LocalDateTime.parse(text)
            "k" -> TempKey(text)
            else -> throw IllegalArgumentException("unknown value tag: $tag")
        }
    }

    private fun tagged(tag: String, text: String): JsonElement = buildJsonArray { add(JsonPrimitive(tag)); add(JsonPrimitive(text)) }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
}
