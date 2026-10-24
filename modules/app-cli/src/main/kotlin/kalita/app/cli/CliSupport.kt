// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.ProgramResult
import kalita.agent.api.ResolveKind
import kalita.agent.api.ResolveTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream

private val PRETTY_JSON = Json { prettyPrint = true }

internal fun prettyJson(element: JsonElement): String = PRETTY_JSON.encodeToString(JsonElement.serializer(), element)

/** `--db <path>`: validated here, never by Clikt — a usage error (exit 2) either way. */
internal fun requireDbFile(path: String, err: PrintStream): File? {
    val file = File(path)
    if (!file.isFile) {
        err.println("no such database: $path")
        return null
    }
    return file
}

/**
 * Stable exit codes (per the CLI's own contract, not dev_plan.md's prose): `0` success, `1`
 * business-logic failure (an explicit `"ok": false`, or a non-empty `"rejected"` list from
 * `add_operations`'s per-draft limit check), `2` usage errors are thrown directly as
 * [ProgramResult] from option/file validation and never reach this function.
 */
internal fun exitCodeFor(result: JsonObject): Int {
    val ok = result["ok"]?.jsonPrimitive?.booleanOrNull ?: true
    if (!ok) return 1
    val rejected = result["rejected"]?.jsonArray
    if (!rejected.isNullOrEmpty()) return 1
    return 0
}

/** Prints [result] as pretty JSON, or [human]'s rendering of it — this CLI is primarily for
 * agents, so the non-JSON path only needs to be a short, honest summary, not a polished table. */
internal fun printResult(result: JsonObject, asJson: Boolean, out: PrintStream, human: (JsonObject) -> String) {
    if (asJson) out.println(prettyJson(result)) else out.println(human(result))
}

/**
 * Resolves one `NAME` argument to an id via [ResolveTool] (dev_plan.md §12.17's own worked
 * example): exact match wins outright; otherwise a lone candidate is accepted; anything else
 * (nothing found, or more than one candidate with no clear winner) prints the candidates and exits
 * non-zero rather than guessing (FR-AG-adjacent: "никаких догадок в справочниках", §12.14).
 */
internal fun resolveIdOrExit(
    resolveTool: ResolveTool,
    query: String,
    kind: ResolveKind,
    label: String,
    asJson: Boolean,
    out: PrintStream,
    err: PrintStream,
): Long {
    val result = resolveTool.execute(query, setOf(kind))
    val candidates = result.getValue("candidates").jsonArray
    val unique = uniqueWinner(candidates)
    if (unique != null) return unique.jsonObject.getValue("id").jsonPrimitive.long
    printResolveFailure(label, query, candidates, asJson, out, err)
    throw ProgramResult(1)
}

private fun uniqueWinner(candidates: JsonArray): JsonElement? = when {
    candidates.isEmpty() -> null
    candidates.size == 1 -> candidates[0]
    else -> {
        val top = candidates[0].jsonObject.getValue("confidence").jsonPrimitive.double
        val second = candidates[1].jsonObject.getValue("confidence").jsonPrimitive.double
        if (top == 1.0 && second < 1.0) candidates[0] else null
    }
}

internal fun printResolveFailure(label: String, query: String, candidates: JsonArray, asJson: Boolean, out: PrintStream, err: PrintStream) {
    if (asJson) {
        out.println(
            prettyJson(
                buildJsonObject {
                    put("ok", false)
                    put("error", "ambiguous_or_not_found")
                    put("field", label)
                    put("query", query)
                    put("candidates", candidates)
                },
            ),
        )
    } else {
        err.println("не удалось однозначно определить $label «$query»:")
        if (candidates.isEmpty()) err.println("  (нет совпадений)")
        candidates.forEach { c ->
            val o = c.jsonObject
            err.println("  ${o.getValue("name").jsonPrimitive.content} (id=${o.getValue("id").jsonPrimitive.content}, confidence=${o.getValue("confidence").jsonPrimitive.content})")
        }
    }
}

internal fun JsonObject.boolOrFalse(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

internal fun JsonObject.ok(): Boolean = this["ok"]?.jsonPrimitive?.boolean ?: true
