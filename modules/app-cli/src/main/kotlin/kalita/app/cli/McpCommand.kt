// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kalita.agent.api.AgentSession
import kalita.agent.api.WriteLimits
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PrintStream
import java.math.BigDecimal

/**
 * `kalita mcp` (dev_plan.md §12.12, FR-AG2): the local MCP server over stdio, for desktop agents
 * (Claude Desktop, Claude Code, …) — `claude mcp add kalita -- kalita mcp`. Unlike every other
 * command in this module, this one does not exit after one call: it blocks for the whole lifetime
 * of the client connection, serving [buildMcpServer]'s tools until the client disconnects.
 */
internal class McpCommand(private val err: PrintStream) : CliktCommand(name = "mcp") {
    private val dbPath by option("--db").required()
    private val mode by option("--mode").default("draft")
    private val maxAmount by option("--max-amount")
    private val maxDaily by option("--max-daily")
    private val device by option("--device").default("agent:mcp")

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val writeMode = parseWriteMode(mode) ?: run {
            err.println("unknown --mode: $mode (expected read-only|draft|write)")
            throw ProgramResult(2)
        }
        val limits = WriteLimits(maxAmount?.let(::BigDecimal), maxDaily?.let(::BigDecimal))

        AgentSession.open(file).use { session ->
            val server = buildMcpServer(session, writeMode, limits, device)
            val transport = StdioServerTransport(input = System.`in`.asSource().buffered(), output = System.out.asSink().buffered())
            runBlocking {
                val mcpSession = server.createSession(transport)
                val done = Job()
                mcpSession.onClose { done.complete() }
                done.join()
            }
        }
    }
}
