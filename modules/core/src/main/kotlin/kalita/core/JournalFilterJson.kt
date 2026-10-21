// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

/**
 * JSON form of a [JournalFilter] and a journal sort — what saved filters (FR-28) and the journal's
 * remembered state (FR-42) store in Kalita's settings. Enum values are written by name, amounts as
 * decimal strings, dates as ISO `yyyy-MM-dd`; flags left at `false` are omitted.
 *
 * Decoding throws [IllegalArgumentException] on anything it does not recognise, including a newer
 * [VERSION] — the caller decides whether to drop such a setting or keep it untouched.
 */
object JournalFilterJson {
    const val VERSION = 1

    fun encode(filter: JournalFilter): JsonObject = buildJsonObject {
        put("version", VERSION)
        put("conditions", JsonArray(filter.conditions.map(::encodeCondition)))
    }

    fun decode(o: JsonObject): JournalFilter = decoding {
        val version = o.req("version").jsonPrimitive.int
        require(version == VERSION) { "unsupported filter version $version" }
        JournalFilter(o.req("conditions").jsonArray.map { decodeCondition(it.jsonObject) })
    }

    fun encodeToString(filter: JournalFilter): String = encode(filter).toString()

    fun decodeFromString(text: String): JournalFilter = decode(Json.parseToJsonElement(text).jsonObject)

    fun encodeSort(sort: List<SortKey>): JsonArray = JsonArray(
        sort.map { key ->
            buildJsonObject {
                put("column", key.column.name)
                if (key.descending) put("descending", true)
            }
        },
    )

    fun decodeSort(a: JsonArray): List<SortKey> = decoding {
        a.map {
            val o = it.jsonObject
            SortKey(JournalColumn.valueOf(o.str("column")), o.flag("descending"))
        }
    }

    // Missing keys and malformed dates surface as other exception types; report them all alike.
    private inline fun <T> decoding(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: RuntimeException) {
        throw IllegalArgumentException(e.message, e)
    }

    private fun encodeCondition(c: FilterCondition): JsonObject = buildJsonObject {
        when (c) {
            is DateCondition -> {
                put("kind", "date")
                put("intervals", JsonArray(c.intervals.map(::encodeInterval)))
            }
            is RefCondition -> {
                put("kind", "ref")
                put("field", c.field.name)
                put("ids", JsonArray(c.ids.sorted().map(::JsonPrimitive)))
                if (c.withDescendants) put("withDescendants", true)
            }
            is AmountCondition -> {
                put("kind", "amount")
                put("min", c.min?.toPlainString())
                put("max", c.max?.toPlainString())
            }
            is TextCondition -> {
                put("kind", "text")
                put("field", c.field.name)
                put("substring", c.substring)
            }
        }
        if (c.includeEmpty) put("includeEmpty", true)
        if (c.inverted) put("inverted", true)
    }

    private fun decodeCondition(o: JsonObject): FilterCondition {
        val empty = o.flag("includeEmpty")
        val inverted = o.flag("inverted")
        return when (val kind = o.str("kind")) {
            "date" -> DateCondition(o.req("intervals").jsonArray.map { decodeInterval(it.jsonObject) }, empty, inverted)
            "ref" -> RefCondition(
                RefField.valueOf(o.str("field")),
                o.req("ids").jsonArray.mapTo(LinkedHashSet()) { it.jsonPrimitive.long },
                o.flag("withDescendants"),
                empty,
                inverted,
            )
            "amount" -> AmountCondition(o.optStr("min")?.let(::BigDecimal), o.optStr("max")?.let(::BigDecimal), empty, inverted)
            "text" -> TextCondition(TextField.valueOf(o.str("field")), o.str("substring"), empty, inverted)
            else -> throw IllegalArgumentException("unknown filter condition kind '$kind'")
        }
    }

    private fun encodeInterval(i: DateInterval): JsonObject = buildJsonObject {
        put("from", encodeBound(i.from))
        put("to", encodeBound(i.to))
    }

    private fun decodeInterval(o: JsonObject) = DateInterval(decodeBound(o["from"]), decodeBound(o["to"]))

    private fun encodeBound(b: DateBound?): JsonElement = when (b) {
        null -> JsonNull
        is DateBound.Fixed -> buildJsonObject { put("fixed", b.date.toString()) }
        is DateBound.Relative -> buildJsonObject { put("relative", b.anchor.name) }
    }

    private fun decodeBound(e: JsonElement?): DateBound? {
        if (e == null || e is JsonNull) return null
        val o = e.jsonObject
        return o.optStr("fixed")?.let { DateBound.Fixed(LocalDate.parse(it)) }
            ?: o.optStr("relative")?.let { DateBound.Relative(DateAnchor.valueOf(it)) }
            ?: throw IllegalArgumentException("date bound needs 'fixed' or 'relative': $o")
    }

    private fun JsonObject.req(key: String): JsonElement =
        get(key) ?: throw IllegalArgumentException("missing '$key' in $this")

    private fun JsonObject.str(key: String): String = req(key).jsonPrimitive.content

    private fun JsonObject.optStr(key: String): String? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.flag(key: String): Boolean = get(key)?.jsonPrimitive?.boolean ?: false
}
