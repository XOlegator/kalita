// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kalita.agent.api.AddOperationsTool
import kalita.agent.api.AgentSession
import kalita.agent.api.ApproveInboxTool
import kalita.agent.api.GetBalancesTool
import kalita.agent.api.GetContextTool
import kalita.agent.api.ListInboxTool
import kalita.agent.api.ListOperationsTool
import kalita.agent.api.RejectInboxTool
import kalita.agent.api.ResolveKind
import kalita.agent.api.ResolveTool
import kalita.agent.api.ToolCatalog
import kalita.agent.api.WriteLimits
import kalita.agent.api.WriteMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import java.math.BigDecimal
import java.time.LocalDate

private const val DEVICE = "agent:cli"

internal class RootCommand(private val err: PrintStream) : CliktCommand(name = "kalita") {
    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            err.println(getFormattedHelp())
            throw ProgramResult(2)
        }
    }
}

/** `kalita context --json [--db <path>]` (dev_plan.md §12.17). */
internal class ContextCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "context") {
    private val dbPath by option("--db").required()
    private val asJson by option("--json").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val result = AgentSession.open(file).use { session -> GetContextTool(session.dataset).execute() }
        printResult(result, asJson, out) { r ->
            val base = r["baseCurrency"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            "Сегодня: ${r.getValue("today").jsonPrimitive.content}. Валюта по умолчанию: ${base ?: "—"}."
        }
        throw ProgramResult(exitCodeFor(result))
    }
}

/** `kalita resolve <query> --kind account|budget-item|currency|counterparty|type --json [--db <path>]`. */
internal class ResolveCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "resolve") {
    private val query by argument()
    private val kindArg by option("--kind").required()
    private val dbPath by option("--db").required()
    private val asJson by option("--json").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val kind = parseResolveKind(kindArg) ?: run {
            err.println("unknown --kind: $kindArg")
            throw ProgramResult(2)
        }
        val result = AgentSession.open(file).use { session -> ResolveTool(session.dataset).execute(query, setOf(kind)) }
        printResult(result, asJson, out) { r ->
            val candidates = r.getValue("candidates").jsonArray
            if (candidates.isEmpty()) {
                "Совпадений не найдено для «$query»."
            } else {
                candidates.joinToString("\n") { c ->
                    val o = c.jsonObject
                    "${o.getValue("name").jsonPrimitive.content} (id=${o.getValue("id").jsonPrimitive.content}, confidence=${o.getValue("confidence").jsonPrimitive.content})"
                }
            }
        }
        throw ProgramResult(0)
    }
}

internal fun parseResolveKind(s: String): ResolveKind? = when (s) {
    "account" -> ResolveKind.Account
    "budget-item" -> ResolveKind.BudgetItem
    "currency" -> ResolveKind.Currency
    "counterparty" -> ResolveKind.Counterparty
    "type" -> ResolveKind.OperationType
    else -> null
}

/** `kalita op` — parent of `list`/`add`. */
internal class OpCommand : CliktCommand(name = "op") {
    override fun run() = Unit
}

/** `kalita op list [--from DATE] [--to DATE] [--category NAME] [--account NAME] [--type NAME]
 * [--currency NAME] [--text SUBSTR] [--sum] [--offset N] [--limit N] --json [--db <path>]`. */
internal class OpListCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "list") {
    private val dbPath by option("--db").required()
    private val from by option("--from")
    private val to by option("--to")
    private val category by option("--category")
    private val account by option("--account")
    private val type by option("--type")
    private val currency by option("--currency")
    private val text by option("--text")
    private val sum by option("--sum").flag()
    private val offset by option("--offset").int().default(0)
    private val limit by option("--limit").int().default(50)
    private val asJson by option("--json").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        AgentSession.open(file).use { session ->
            val dataset = session.dataset
            val resolveTool = ResolveTool(dataset)
            val budgetItemIds = category?.let { setOf(resolveIdOrExit(resolveTool, it, ResolveKind.BudgetItem, "статью", asJson, out, err)) }.orEmpty()
            val accountIds = account?.let { setOf(resolveIdOrExit(resolveTool, it, ResolveKind.Account, "счёт", asJson, out, err)) }.orEmpty()
            val typeIds = type?.let { setOf(resolveIdOrExit(resolveTool, it, ResolveKind.OperationType, "тип операции", asJson, out, err)) }.orEmpty()
            val currencyIds = currency?.let { setOf(resolveIdOrExit(resolveTool, it, ResolveKind.Currency, "валюту", asJson, out, err)) }.orEmpty()
            val result = ListOperationsTool(dataset).execute(
                dateFrom = from?.let(LocalDate::parse),
                dateTo = to?.let(LocalDate::parse),
                budgetItemIds = budgetItemIds,
                accountIds = accountIds,
                currencyIds = currencyIds,
                typeIds = typeIds,
                commentSubstring = text,
                offset = offset,
                limit = limit,
            )
            printResult(result, asJson, out) { r ->
                buildString {
                    append("Найдено операций: ${r.getValue("totalCount").jsonPrimitive.content}\n")
                    r.getValue("rows").jsonArray.forEach { row ->
                        val o = row.jsonObject
                        append("${o["date"]?.jsonPrimitive?.content} ${o["amount"]?.jsonPrimitive?.content} ${o["budgetItem"]?.jsonPrimitive?.content ?: ""}\n")
                    }
                    if (sum) append("Сумма: ${r.getValue("aggregate").jsonObject.getValue("sumAmount").jsonPrimitive.content}")
                }
            }
            throw ProgramResult(exitCodeFor(result))
        }
    }
}

/** `kalita op add ...` — a single operation from flags, or `--batch <file.json>` (dev_plan.md §12.17). */
internal class OpAddCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "add") {
    private val dbPath by option("--db").required()
    private val batch by option("--batch")
    private val type by option("--type")
    private val amount by option("--amount")
    private val category by option("--category")
    private val account by option("--account")
    private val accountTo by option("--account-to")
    private val date by option("--date")
    private val comment by option("--comment")
    private val dryRun by option("--dry-run").flag()
    private val mode by option("--mode").default("draft")
    private val requestId by option("--request-id").required()
    private val maxAmount by option("--max-amount")
    private val maxDaily by option("--max-daily")
    private val asJson by option("--json").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val writeMode = parseWriteMode(mode) ?: run {
            err.println("unknown --mode: $mode (expected read-only|draft|write)")
            throw ProgramResult(2)
        }
        val limits = WriteLimits(maxAmount?.let(::BigDecimal), maxDaily?.let(::BigDecimal))

        AgentSession.open(file).use { session ->
            val dataset = session.dataset
            val resolveTool = ResolveTool(dataset)
            val today = LocalDate.now()
            val paramsList = if (batch != null) {
                readBatch(File(requireNotNull(batch)))
            } else {
                listOf(singleParams())
            }
            val drafts = paramsList.map { buildDraft(dataset, resolveTool, it, today, asJson, out, err) }

            val result = AddOperationsTool(session).execute(
                drafts = drafts,
                mode = writeMode,
                limits = limits,
                dryRun = dryRun,
                clientRequestId = requestId,
                device = DEVICE,
                today = today,
            )
            printResult(result, asJson, out) { r -> humanOpAddSummary(r) }
            throw ProgramResult(exitCodeFor(result))
        }
    }

    private fun singleParams(): OpAddParams = OpAddParams(
        type = requireFlag(type, "--type"),
        amount = BigDecimal(requireFlag(amount, "--amount")),
        category = requireFlag(category, "--category"),
        account = requireFlag(account, "--account"),
        accountTo = accountTo,
        date = requireFlag(date, "--date"),
        comment = comment,
    )

    private fun requireFlag(value: String?, name: String): String = value ?: run {
        err.println("missing required option: $name (or use --batch)")
        throw ProgramResult(2)
    }

    private fun readBatch(file: File): List<OpAddParams> {
        if (!file.isFile) {
            err.println("no such batch file: ${file.path}")
            throw ProgramResult(2)
        }
        val array = Json.parseToJsonElement(file.readText()).jsonArray
        return array.map { parseOpAddParams(it.jsonObject) }
    }
}

private fun humanOpAddSummary(r: JsonObject): String {
    if (!r.ok()) return "Ошибка: ${r["error"]?.jsonPrimitive?.content ?: r}"
    val accepted = r["accepted"]?.jsonArray?.size ?: 0
    val rejected = r["rejected"]?.jsonArray?.size ?: 0
    val mode = r["mode"]?.jsonPrimitive?.content ?: "?"
    val dry = if (r.boolOrFalse("dryRun")) " (предпросмотр)" else ""
    return "Режим $mode$dry: принято $accepted, отклонено $rejected."
}

internal fun parseWriteMode(s: String): WriteMode? = when (s) {
    "read-only" -> WriteMode.ReadOnly
    "draft" -> WriteMode.Draft
    "write" -> WriteMode.Write
    else -> null
}

/** `kalita balance --json [--db <path>]`. */
internal class BalanceCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "balance") {
    private val dbPath by option("--db").required()
    private val asJson by option("--json").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val result = AgentSession.open(file).use { session -> GetBalancesTool(session.dataset).execute() }
        printResult(result, asJson, out) { r ->
            val total = r.getValue("total").jsonObject
            "ИТОГО: получено ${total.getValue("received").jsonPrimitive.content}, потрачено ${total.getValue("spent").jsonPrimitive.content}, остаток ${total.getValue("balance").jsonPrimitive.content}."
        }
        throw ProgramResult(exitCodeFor(result))
    }
}

/** `kalita inbox` — parent of `list`/`approve`/`reject`. */
internal class InboxCommand : CliktCommand(name = "inbox") {
    override fun run() = Unit
}

internal class InboxListCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "list") {
    private val dbPath by option("--db").required()
    private val asJson by option("--json").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val result = AgentSession.open(file).use { session -> ListInboxTool(session).execute() }
        printResult(result, asJson, out) { r ->
            val entries = r.getValue("entries").jsonArray
            if (entries.isEmpty()) "Входящих нет." else entries.joinToString("\n") { it.jsonObject.getValue("id").jsonPrimitive.content }
        }
        throw ProgramResult(0)
    }
}

internal class InboxApproveCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "approve") {
    private val inboxId by argument()
    private val dbPath by option("--db").required()
    private val asJson by option("--json").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val result = AgentSession.open(file).use { session -> ApproveInboxTool(session).execute(inboxId) }
        printResult(result, asJson, out) { r -> if (r.ok()) "Подтверждено: $inboxId" else "Ошибка: ${r["error"]?.jsonPrimitive?.content}" }
        throw ProgramResult(exitCodeFor(result))
    }
}

internal class InboxRejectCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "reject") {
    private val inboxId by argument()
    private val dbPath by option("--db").required()
    private val asJson by option("--json").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val result = AgentSession.open(file).use { session -> RejectInboxTool(session).execute(inboxId) }
        printResult(result, asJson, out) { r -> if (r.ok()) "Отклонено: $inboxId" else "Ошибка: ${r["error"]?.jsonPrimitive?.content}" }
        throw ProgramResult(exitCodeFor(result))
    }
}

/** `kalita schema --json` (FR-AG1): a structured description of every tool, for an agent or a human skimming it. */
internal class SchemaCommand(private val out: PrintStream) : CliktCommand(name = "schema") {
    private val asJson by option("--json").flag()

    override fun run() {
        val json = buildJsonObject { put("tools", ToolCatalog.toJson()) }
        if (asJson) {
            out.println(prettyJson(json))
        } else {
            ToolCatalog.tools.forEach { out.println("${it.name} [${it.access}] — ${it.description}") }
        }
        throw ProgramResult(0)
    }
}
