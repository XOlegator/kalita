// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kalita.agent.api.AgentSession
import kalita.agent.api.GetReceiptHintsTool
import kalita.agent.api.GetReceiptProposalTool
import kalita.agent.api.ProposeReceiptTool
import kalita.agent.api.ReceiptProposalInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintStream

/** `kalita receipt` — parent of `propose`/`get`/`hints` (dev_plan.md §12.19: the receipt review window). */
internal class ReceiptCommand : CliktCommand(name = "receipt") {
    override fun run() = Unit
}

/** `kalita receipt propose --file receipt.json --request-id ID [--mode draft|write] --json [--db <path>]`. */
internal class ReceiptProposeCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "propose") {
    private val dbPath by option("--db").required()
    private val file by option("--file").required()
    private val requestId by option("--request-id").required()
    private val modeName by option("--mode").default("draft")
    private val device by option("--device").default("agent:cli")
    private val asJson by option("--json").flag()

    override fun run() {
        val db = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val mode = parseWriteMode(modeName) ?: run {
            err.println("unknown --mode: $modeName")
            throw ProgramResult(2)
        }
        val input = File(file).takeIf { it.isFile }?.let { ReceiptProposalInput.fromJson(Json.parseToJsonElement(it.readText()).jsonObject) } ?: run {
            err.println("no such receipt file: $file")
            throw ProgramResult(2)
        }
        val result = AgentSession.open(db).use { session -> ProposeReceiptTool(session).execute(input, mode, requestId, device) }
        printResult(result, asJson, out) { r ->
            if (r.ok()) "Чек отправлен на проверку пользователю: ${r.getValue("proposalId").jsonPrimitive.content}" else "Ошибка: ${r["error"]?.jsonPrimitive?.content}"
        }
        throw ProgramResult(exitCodeFor(result))
    }
}

/** `kalita receipt get PROPOSAL_ID [--wait SECONDS] --json [--db <path>]` — the person's decision; `--wait` polls while it is still pending. */
internal class ReceiptGetCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "get") {
    private val proposalId by argument()
    private val dbPath by option("--db").required()
    private val wait by option("--wait").int().default(0)
    private val asJson by option("--json").flag()

    override fun run() {
        val db = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val deadline = System.nanoTime() + wait * 1_000_000_000L
        var result = AgentSession.open(db).use { GetReceiptProposalTool(it).execute(proposalId) }
        while (result.ok() && result["status"]?.jsonPrimitive?.content == "pending" && System.nanoTime() < deadline) {
            Thread.sleep(1000)
            result = AgentSession.open(db).use { GetReceiptProposalTool(it).execute(proposalId) }
        }
        printResult(result, asJson, out) { r -> if (r.ok()) "Статус: ${r.getValue("status").jsonPrimitive.content}" else "Ошибка: ${r["error"]?.jsonPrimitive?.content}" }
        throw ProgramResult(exitCodeFor(result))
    }
}

/** `kalita receipt hints --text "Кофе" --text "Хлеб" [--merchant NAME] --json [--db <path>]`. */
internal class ReceiptHintsCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "hints") {
    private val dbPath by option("--db").required()
    private val texts by option("--text").multiple(required = true)
    private val merchant by option("--merchant")
    private val asJson by option("--json").flag()

    override fun run() {
        val db = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val result = AgentSession.open(db).use { GetReceiptHintsTool(it).execute(texts, merchant) }
        printResult(result, asJson, out) { r -> "Подсказок: ${r.getValue("hints").let { (it as kotlinx.serialization.json.JsonArray).size }}" }
        throw ProgramResult(0)
    }
}
