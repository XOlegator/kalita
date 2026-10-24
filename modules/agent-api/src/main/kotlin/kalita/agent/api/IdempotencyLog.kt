// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/**
 * `client_request_id` replay guard for [AddOperationsTool] (dev_plan.md §12.14): one line per
 * applied request in `<dataDirectory>/agent-idempotency.jsonl`. Read in full on every lookup —
 * these logs are small (one line per agent write), an index would be overkill for this slice.
 */
internal class IdempotencyLog(dataDirectory: File) {
    private val file = File(dataDirectory, "agent-idempotency.jsonl")

    /** The recorded result of a previous call with the same [clientRequestId], if any. */
    fun find(clientRequestId: String): JsonObject? {
        if (!file.isFile) return null
        return file.readLines()
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it.getValue("clientRequestId").jsonPrimitive.content == clientRequestId }
            ?.getValue("result")?.jsonObject
    }

    /** Appends [result] — only called after a successful, non-dry-run apply. */
    fun record(clientRequestId: String, result: JsonObject) {
        file.parentFile.mkdirs()
        val line = buildJsonObject {
            put("clientRequestId", clientRequestId)
            put("appliedAt", Instant.now().toString())
            put("result", result)
        }
        file.appendText(line.toString() + "\n")
    }
}
