// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal

/**
 * One named agent connection's settings (dev_plan.md §12.14: "режимы записи... настройка в
 * программе, отдельно для каждого подключения") — e.g. "Claude Desktop", "Claude Code CLI". [name]
 * is the key a human picks in the desktop app's settings screen (or `kalita agent add`) and the
 * same string `kalita mcp --connection` is given, not a generated id.
 */
data class AgentConnection(val name: String, val mode: WriteMode, val limits: WriteLimits = WriteLimits())

/**
 * Named connections persisted per database (`<dataDirectory>/agent-connections.json`), so the
 * desktop app's settings screen and `kalita mcp --connection <name>` (dev_plan.md §12.15) agree on
 * the same list without either side hardcoding it. Whole-file rewrite on every change, same
 * simplicity-first choice [InboxStore] documents for a file this small.
 */
class AgentConnectionStore(dataDirectory: File) {
    private val file = File(dataDirectory, "agent-connections.json")

    fun list(): List<AgentConnection> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        return Json.parseToJsonElement(text).jsonArray.map(::decode)
    }

    fun find(name: String): AgentConnection? = list().firstOrNull { it.name == name }

    /** Adds [connection], or replaces the existing one with the same [AgentConnection.name]. */
    fun upsert(connection: AgentConnection) {
        writeAll(list().filterNot { it.name == connection.name } + connection)
    }

    fun remove(name: String) {
        writeAll(list().filterNot { it.name == name })
    }

    private fun writeAll(connections: List<AgentConnection>) {
        file.parentFile.mkdirs()
        file.writeText(JsonArray(connections.map(::encode)).toString())
    }

    private fun encode(c: AgentConnection): JsonObject = buildJsonObject {
        put("name", c.name)
        put("mode", c.mode.name)
        c.limits.maxOperationAmount?.let { put("maxOperationAmount", it.toPlainString()) }
        c.limits.maxDailyAmount?.let { put("maxDailyAmount", it.toPlainString()) }
    }

    private fun decode(e: JsonElement): AgentConnection {
        val o = e.jsonObject
        return AgentConnection(
            name = o.getValue("name").jsonPrimitive.content,
            mode = WriteMode.valueOf(o.getValue("mode").jsonPrimitive.content),
            limits = WriteLimits(
                maxOperationAmount = o["maxOperationAmount"]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal),
                maxDailyAmount = o["maxDailyAmount"]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal),
            ),
        )
    }
}
