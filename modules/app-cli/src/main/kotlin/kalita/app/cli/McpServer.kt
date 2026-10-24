// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
import kalita.agent.api.WriteLimits
import kalita.agent.api.WriteMode
import kalita.core.OperationDraft
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

// `kalita mcp` (dev_plan.md §12.12/§12.13, FR-AG2): the local stdio MCP server, over the official
// Kotlin MCP SDK, exposing the same agent-api tools the `kalita` CLI already exposes. Built here as
// a plain function returning a `Server` (not inside `McpCommand`) so tests can drive it in-process
// via the SDK's `ChannelTransport`, without stdio or a subprocess — see `McpServerTest`.
//
// Deferred to a later slice, not stubbed here (same style as `AgentApiModule`'s own deferred-scope
// note):
// - MCP Resources (`kalita://context`, `kalita://dictionaries/{name}`, …) and MCP Prompts
//   ("Внести траты за день", …) — §12.13's own extra bullets beyond the tools table.
// - The local socket channel to a running desktop instance (§12.15/FR-AG6) — a concurrently-open
//   `.mdb` still just comes back read-only via the existing `DatabaseLock`, same as the CLI slice.
// - Elicitation-based confirmation prompts ("Записать N операций на сумму …?").

/** Snake_case MCP wire spelling for [ResolveKind] — distinct from `parseResolveKind` in
 * Commands.kt (the CLI's own `--kind` flag spelling), because this is the model-facing MCP tool
 * contract, not a CLI flag. */
private fun parseResolveKindMcp(s: String): ResolveKind = when (s) {
    "account" -> ResolveKind.Account
    "budget_item" -> ResolveKind.BudgetItem
    "currency" -> ResolveKind.Currency
    "counterparty" -> ResolveKind.Counterparty
    "operation_type" -> ResolveKind.OperationType
    else -> error("unknown kind: $s")
}

private val RESOLVE_KIND_WORDS = listOf("account", "budget_item", "currency", "counterparty", "operation_type")

private fun typeSchema(type: String): JsonObject = buildJsonObject { put("type", type) }

private fun arraySchema(itemType: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", typeSchema(itemType))
}

/**
 * Builds one [OperationDraft] from a `drafts[]` element of `add_operations`'s arguments. Mirrors
 * `kalita.agent.api.OperationDraftJson.decode` (`internal` to `agent-api`, not visible from here) —
 * the same small, intentional duplication `draftToColumns`'s own KDoc there documents: this module
 * already re-implements similarly small mappings on purpose rather than widen agent-api's public
 * surface for one caller. Every field but `amount` is optional here, same as that decode — a
 * missing/invalid field is [kalita.core.OperationRules.validate]'s job to catch, not this parser's.
 */
private fun decodeMcpDraft(o: JsonObject): OperationDraft = OperationDraft(
    date = o["date"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
    amount = o["amount"]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal),
    currencyId = o["currencyId"]?.jsonPrimitive?.longOrNull,
    budgetItemId = o["budgetItemId"]?.jsonPrimitive?.longOrNull,
    typeId = o["typeId"]?.jsonPrimitive?.longOrNull,
    comment = o["comment"]?.jsonPrimitive?.contentOrNull,
    counterpartyId = o["counterpartyId"]?.jsonPrimitive?.longOrNull,
    accountFrom = o["accountFrom"]?.jsonPrimitive?.longOrNull,
    accountTo = o["accountTo"]?.jsonPrimitive?.longOrNull,
)

private fun JsonObject.toCallToolResult(): CallToolResult =
    CallToolResult(content = listOf(TextContent(toString())), isError = !ok())

/**
 * Builds the `kalita mcp` server for one connection: [mode]/[limits]/[device] are fixed for the
 * whole process, mirroring dev_plan.md §12.14's "per-connection" setting — unlike the CLI's
 * `op add --mode`/`--max-amount` flags, which exist precisely because one CLI invocation is *not* a
 * persistent connection.
 *
 * Which tools even appear in `tools/list` is gated by [mode]:
 * - [WriteMode.ReadOnly] registers only the five read tools.
 * - [WriteMode.Draft] additionally registers `add_operations` (which, in this mode, only ever
 *   appends to `inbox.jsonl` — [AddOperationsTool] enforces that itself). `approve_inbox` and
 *   `reject_inbox` are deliberately **not** registered: a Draft-level connection is not trusted to
 *   push its own (or anyone else's) pending draft into the database — that would defeat the
 *   human-in-the-loop point of Draft mode. A future "connection settings" slice may want a separate
 *   "may approve" permission; no such flag exists yet, so this slice ties approval strictly to
 *   [WriteMode.Write].
 * - [WriteMode.Write] registers everything: the five read tools, `add_operations`, `approve_inbox`
 *   and `reject_inbox`.
 *
 * Every handler calls [AgentSession.refresh] first (see that method's KDoc): `kalita mcp` is a
 * long-lived process, unlike the CLI, so without it the server would keep serving the dataset
 * exactly as of process start.
 */
internal fun buildMcpServer(session: AgentSession, mode: WriteMode, limits: WriteLimits, device: String): Server {
    val server = Server(
        serverInfo = Implementation(name = "kalita", version = "1.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    registerReadTools(server, session)
    if (mode != WriteMode.ReadOnly) {
        registerAddOperationsTool(server, session, mode, limits, device)
    }
    if (mode == WriteMode.Write) {
        registerInboxWriteTools(server, session)
    }
    return server
}

private fun registerReadTools(server: Server, session: AgentSession) {
    server.addTool(
        name = "get_context",
        description = "Today's date, base currency, live dictionaries (accounts, currencies, counterparties, " +
            "budget tree, operation types).",
        inputSchema = ToolSchema(),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { _ ->
        session.refresh()
        GetContextTool(session.dataset).execute().toCallToolResult()
    }

    server.addTool(
        name = "resolve",
        description = "Name lookup across one or more dictionaries with a match confidence, for " +
            "disambiguation before calling a write tool.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", typeSchema("string"))
                put(
                    "kinds",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(RESOLVE_KIND_WORDS.map(::JsonPrimitive)))
                            },
                        )
                    },
                )
                put("limit", typeSchema("integer"))
            },
            required = listOf("query", "kinds"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val query = args.getValue("query").jsonPrimitive.content
        val kinds = args.getValue("kinds").jsonArray.map { parseResolveKindMcp(it.jsonPrimitive.content) }.toSet()
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10
        ResolveTool(session.dataset).execute(query, kinds, limit).toCallToolResult()
    }

    server.addTool(
        name = "list_operations",
        description = "Journal search by date/account/budget item/currency/counterparty/type/amount/comment, " +
            "paginated, with aggregate sums.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("dateFrom", typeSchema("string"))
                put("dateTo", typeSchema("string"))
                put("budgetItemIds", arraySchema("integer"))
                put("accountIds", arraySchema("integer"))
                put("currencyIds", arraySchema("integer"))
                put("counterpartyIds", arraySchema("integer"))
                put("typeIds", arraySchema("integer"))
                put("amountMin", typeSchema("string"))
                put("amountMax", typeSchema("string"))
                put("commentSubstring", typeSchema("string"))
                put("offset", typeSchema("integer"))
                put("limit", typeSchema("integer"))
            },
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        fun longSet(key: String): Set<Long> = args[key]?.jsonArray?.map { it.jsonPrimitive.long }?.toSet().orEmpty()
        ListOperationsTool(session.dataset).execute(
            dateFrom = args["dateFrom"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
            dateTo = args["dateTo"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
            budgetItemIds = longSet("budgetItemIds"),
            accountIds = longSet("accountIds"),
            currencyIds = longSet("currencyIds"),
            counterpartyIds = longSet("counterpartyIds"),
            typeIds = longSet("typeIds"),
            amountMin = args["amountMin"]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal),
            amountMax = args["amountMax"]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal),
            commentSubstring = args["commentSubstring"]?.jsonPrimitive?.contentOrNull,
            offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0,
            limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50,
        ).toCallToolResult()
    }

    server.addTool(
        name = "get_balances",
        description = "Account balances per currency and the ИТОГО total in the reduction currency.",
        inputSchema = ToolSchema(properties = buildJsonObject { put("reductionCurrencyId", typeSchema("integer")) }),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val reductionCurrencyId = args["reductionCurrencyId"]?.jsonPrimitive?.longOrNull
        val tool = GetBalancesTool(session.dataset)
        val result = if (reductionCurrencyId != null) tool.execute(reductionCurrencyId) else tool.execute()
        result.toCallToolResult()
    }

    server.addTool(
        name = "list_inbox",
        description = "Pending drafts awaiting a human's confirmation.",
        inputSchema = ToolSchema(),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { _ ->
        session.refresh()
        ListInboxTool(session).execute().toCallToolResult()
    }
}

private fun registerAddOperationsTool(server: Server, session: AgentSession, mode: WriteMode, limits: WriteLimits, device: String) {
    server.addTool(
        name = "add_operations",
        description = "Add one or more operations, as drafts in «Входящие» or straight to the database, with " +
            "validation, per-operation/daily limits, a dry-run preview and idempotent replay.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "drafts",
                    buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("date", typeSchema("string"))
                                        put("amount", typeSchema("string"))
                                        put("currencyId", typeSchema("integer"))
                                        put("budgetItemId", typeSchema("integer"))
                                        put("typeId", typeSchema("integer"))
                                        put("counterpartyId", typeSchema("integer"))
                                        put("accountFrom", typeSchema("integer"))
                                        put("accountTo", typeSchema("integer"))
                                        put("comment", typeSchema("string"))
                                    },
                                )
                                put("required", JsonArray(listOf(JsonPrimitive("amount"))))
                            },
                        )
                    },
                )
                put("dryRun", typeSchema("boolean"))
                put("clientRequestId", typeSchema("string"))
            },
            required = listOf("drafts", "clientRequestId"),
        ),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val drafts = args.getValue("drafts").jsonArray.map { decodeMcpDraft(it.jsonObject) }
        val dryRun = args["dryRun"]?.jsonPrimitive?.booleanOrNull ?: false
        val clientRequestId = args.getValue("clientRequestId").jsonPrimitive.content
        AddOperationsTool(session).execute(
            drafts = drafts,
            mode = mode,
            limits = limits,
            dryRun = dryRun,
            clientRequestId = clientRequestId,
            device = device,
        ).toCallToolResult()
    }
}

private fun registerInboxWriteTools(server: Server, session: AgentSession) {
    server.addTool(
        name = "approve_inbox",
        description = "Commit one pending draft straight to the database.",
        inputSchema = ToolSchema(properties = buildJsonObject { put("inboxId", typeSchema("string")) }, required = listOf("inboxId")),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val inboxId = args.getValue("inboxId").jsonPrimitive.content
        ApproveInboxTool(session).execute(inboxId).toCallToolResult()
    }

    server.addTool(
        name = "reject_inbox",
        description = "Mark one pending draft as rejected, without touching the database.",
        inputSchema = ToolSchema(properties = buildJsonObject { put("inboxId", typeSchema("string")) }, required = listOf("inboxId")),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val inboxId = args.getValue("inboxId").jsonPrimitive.content
        RejectInboxTool(session).execute(inboxId).toCallToolResult()
    }
}
