// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kalita.agent.api.AddDictionaryItemTool
import kalita.agent.api.AddOperationsTool
import kalita.agent.api.AgentSession
import kalita.agent.api.ApproveInboxTool
import kalita.agent.api.DeleteOperationTool
import kalita.agent.api.DictionaryKind
import kalita.agent.api.GetBalancesTool
import kalita.agent.api.GetContextTool
import kalita.agent.api.GetReceiptHintsTool
import kalita.agent.api.GetReceiptProposalTool
import kalita.agent.api.ProposeReceiptTool
import kalita.agent.api.ReceiptProposalInput
import kalita.agent.api.GetSpendingSummaryTool
import kalita.agent.api.ListInboxTool
import kalita.agent.api.ListOperationsTool
import kalita.agent.api.RejectInboxTool
import kalita.agent.api.ResolveKind
import kalita.agent.api.ResolveTool
import kalita.agent.api.RestoreOperationTool
import kalita.agent.api.UndoChangesTool
import kalita.agent.api.UpdateOperationTool
import kalita.agent.api.WriteLimits
import kalita.agent.api.WriteMode
import kalita.core.Dataset
import kalita.core.OperationDraft
import kalita.core.RateService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
// MCP Resources and Prompts (registered by `registerResources`/`registerPrompts` below) are
// available on every connection regardless of [WriteMode] — like the read tools, they are either
// read-only (resources) or pure text guidance (prompts), so there is nothing for the per-mode gate
// in `buildMcpServer`'s own KDoc to apply to.
//
// Elicitation-based confirmation ("Записать N операций на сумму …?", dev_plan.md §12.14) is wired
// into `add_operations` only (registerAddOperationsTool/confirmAddOperations below) — the other
// write-only tools (registerWriteOnlyTools) are single-record edits/deletes/restores an agent calls
// deliberately and by id, not the "agent proposes, human approves the batch" flow §12.14 describes;
// `add_operations` in draft mode already gets that same human-in-the-loop review via «Входящие»,
// so this only matters for `add_operations` in write mode. `kotlin-sdk-server` 0.16.0's own
// `ClientConnectionImpl.createElicitation` (confirmed by disassembly) only asserts the client's
// `elicitation` capability for its URL-mode variant, not the plain form-mode one used here — an
// unchecked call to a client that never declared `elicitation` would just hang waiting for a
// response that never arrives, so [confirmAddOperations] checks `ServerSession.clientCapabilities`
// itself before ever sending the request, and simply skips confirmation (today's pre-elicitation
// behavior) for a client that lacks it.
//
// The local socket channel to a running desktop instance (§12.15/FR-AG6) needs no change here: it
// lives under `AgentSession.submit`/`kalita.storage.local.AgentChannelClient`, so every write tool
// above already goes through it transparently — a concurrently-open `.mdb` now has its change sets
// forwarded to the desktop instance instead of merely coming back read-only.

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

/** Snake_case MCP wire spelling for [DictionaryKind] — distinct from `parseDictionaryKind` in
 * Commands.kt (the CLI's own `--kind` flag spelling), same reasoning as [parseResolveKindMcp]. */
private fun parseDictionaryKindMcp(s: String): DictionaryKind = when (s) {
    "account" -> DictionaryKind.Account
    "budget_item" -> DictionaryKind.BudgetItem
    "counterparty" -> DictionaryKind.Counterparty
    "currency" -> DictionaryKind.Currency
    else -> error("unknown kind: $s")
}

private val DICTIONARY_KIND_WORDS = listOf("account", "budget_item", "counterparty", "currency")

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
 * - [WriteMode.Write] registers everything: the five read tools, `add_operations`, `approve_inbox`,
 *   `reject_inbox`, and the write-mode-only tools `update_operation`/`delete_operation`/
 *   `restore_operation`/`add_dictionary_item`/`undo_changes` (dev_plan.md §12.14: these have no
 *   `draft` fallback, so they are gated the same way `approve_inbox`/`reject_inbox` already are
 *   above).
 *
 * Every handler calls [AgentSession.refresh] first (see that method's KDoc): `kalita mcp` is a
 * long-lived process, unlike the CLI, so without it the server would keep serving the dataset
 * exactly as of process start.
 */
internal fun buildMcpServer(session: AgentSession, mode: WriteMode, limits: WriteLimits, device: String): Server {
    val server = Server(
        serverInfo = Implementation(name = "kalita", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
                prompts = ServerCapabilities.Prompts(listChanged = false),
            ),
        ),
    )

    registerReadTools(server, session)
    registerResources(server, session)
    registerPrompts(server)
    registerReceiptReadTools(server, session)
    if (mode != WriteMode.ReadOnly) {
        registerAddOperationsTool(server, session, mode, limits, device)
        registerProposeReceiptTool(server, session, mode, device)
    }
    if (mode == WriteMode.Write) {
        registerInboxWriteTools(server, session)
        registerWriteOnlyTools(server, session, device)
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
        name = "get_spending_summary",
        description = "Income/expense totals by budget item for a period, defaulting to the current month. " +
            "Period-to-period comparison and the rest of dev_plan.md §12.8's analytics wait for release 1.5.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("dateFrom", typeSchema("string"))
                put("dateTo", typeSchema("string"))
                put("reductionCurrencyId", typeSchema("integer"))
            },
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        GetSpendingSummaryTool(session.dataset).execute(
            dateFrom = args["dateFrom"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
            dateTo = args["dateTo"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
            reductionCurrencyId = args["reductionCurrencyId"]?.jsonPrimitive?.longOrNull
                ?: RateService.of(session.dataset).baseCurrencyId,
        ).toCallToolResult()
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

/** Snake/kebab-case names [dictionaryJson] accepts for `kalita://dictionaries/{name}` — distinct
 * from [DICTIONARY_KIND_WORDS] (the `add_dictionary_item` kind), because this lists every live
 * dictionary §12.13's own `get_context` bullet enumerates, including `operation-types` (fixed
 * system data, §4.3 — never a `add_dictionary_item` kind). */
private val DICTIONARY_RESOURCE_NAMES = listOf("accounts", "currencies", "counterparties", "budget-items", "operation-types")

/** Same `{id, name}` shape [kalita.agent.api.GetContextTool] already returns for each dictionary —
 * duplicated here on purpose (that helper is `internal` to `agent-api`), same reasoning as
 * [decodeMcpDraft]'s own KDoc. */
private fun idNameJson(id: Long, name: String?): JsonObject = buildJsonObject { put("id", id); put("name", name) }

/** `kalita://dictionaries/{name}` (§12.13): one live dictionary as a JSON array, or `null` for an
 * unrecognised [name] — [registerResources] turns that into a resource-not-found error. */
private fun dictionaryJson(dataset: Dataset, name: String): JsonArray? = when (name) {
    "accounts" -> JsonArray(dataset.accounts.filter { !it.deleted }.map { idNameJson(it.id, it.name) })
    "currencies" -> JsonArray(dataset.currencies.filter { !it.deleted }.map { idNameJson(it.id, it.name) })
    "counterparties" -> JsonArray(dataset.counterparties.filter { !it.deleted }.map { idNameJson(it.id, it.name) })
    "budget-items" -> JsonArray(
        dataset.budgetItems.filter { !it.deleted }.map { item ->
            buildJsonObject { put("id", item.id); put("name", item.name); put("parentId", item.parentId) }
        },
    )
    "operation-types" -> JsonArray(
        dataset.operationTypes.filter { !it.deleted }.map { type ->
            buildJsonObject { put("id", type.id); put("name", type.name); put("kind", type.kind?.name) }
        },
    )
    else -> null
}

private fun jsonResourceContents(uri: String, json: JsonElement): ReadResourceResult =
    ReadResourceResult(contents = listOf(TextResourceContents(uri = uri, mimeType = "application/json", text = json.toString())))

/**
 * MCP Resources (dev_plan.md §12.13's own extra bullet, beyond the tools table): the same live data
 * [registerReadTools]'s tools expose, for MCP clients that pull context by resource URI instead of
 * (or in addition to) calling a tool — e.g. a client that attaches `kalita://context` to every
 * conversation automatically. Registered for every connection regardless of [WriteMode] — see this
 * file's own top-of-file note.
 */
private fun registerResources(server: Server, session: AgentSession) {
    server.addResource(
        uri = "kalita://context",
        name = "context",
        description = "Today's date, base currency and live dictionaries — same as the get_context tool.",
        mimeType = "application/json",
    ) { request ->
        session.refresh()
        jsonResourceContents(request.uri, GetContextTool(session.dataset).execute())
    }

    server.addResourceTemplate(
        uriTemplate = "kalita://dictionaries/{name}",
        name = "dictionaries",
        description = "One live dictionary by name: ${DICTIONARY_RESOURCE_NAMES.joinToString()}.",
        mimeType = "application/json",
    ) { request, variables ->
        session.refresh()
        val name = variables["name"] ?: error("missing {name} in kalita://dictionaries/{name}")
        val json = dictionaryJson(session.dataset, name) ?: error("unknown dictionary: $name")
        jsonResourceContents(request.uri, json)
    }

    // Not a resource *template*: [io.modelcontextprotocol.kotlin.sdk.utils.PathSegmentTemplateMatcher]
    // (confirmed by disassembling kotlin-sdk-core 0.16.0 — no other matcher ships) only matches plain
    // `{name}` path segments, not RFC6570 query expansion (`{?a,b,...}`) — a template spelled that way
    // simply never matches, including the bare `kalita://operations` URI with no query string at all.
    // A static resource with no filters mirrors `list_operations` called with no arguments; an agent
    // that needs the filters §12.13's own `kalita://operations?…` sketch implies should call that tool
    // directly, which already has them.
    server.addResource(
        uri = "kalita://operations",
        name = "operations",
        description = "Most recent operations, unfiltered — for filtering, call the list_operations tool instead.",
        mimeType = "application/json",
    ) { request ->
        session.refresh()
        jsonResourceContents(request.uri, ListOperationsTool(session.dataset).execute())
    }
}

private fun textPrompt(role: Role, text: String): PromptMessage = PromptMessage(role, TextContent(text))

/**
 * MCP Prompts (dev_plan.md §12.13's own extra bullet): canned task templates a client can surface to
 * the user ("Внести траты за день", …) instead of the user having to type the equivalent request out
 * — each one is plain guidance text for the agent, the same `get_context` → `resolve` →
 * `add_operations` dry-run-then-confirm sequence §12.16's own skill (a later slice) will also
 * document, not a tool call itself. Registered for every connection regardless of [WriteMode] — a
 * `read-only`/`draft` connection's own tool gating still applies once the agent acts on the prompt.
 */
private fun registerPrompts(server: Server) {
    server.addPrompt(
        name = "log_daily_spending",
        description = "Внести траты за день: расспросить про сегодняшние операции и добавить их через предпросмотр.",
        arguments = emptyList(),
    ) { _ ->
        GetPromptResult(
            messages = listOf(
                textPrompt(
                    Role.User,
                    "Расспроси пользователя о сегодняшних доходах и расходах (сумма, статья, счёт, " +
                        "контрагент). Для каждого названия вызови resolve, чтобы найти id в справочниках " +
                        "или уточнить при неоднозначности. Собери операции и вызови add_operations с " +
                        "dry_run=true, покажи предпросмотр пользователю и только после подтверждения " +
                        "вызови add_operations ещё раз без dry_run (или с режимом draft/write подключения).",
                ),
            ),
        )
    }

    server.addPrompt(
        name = "process_receipt",
        description = "Разнести чек: превратить позиции чека в операции по статьям.",
        arguments = listOf(PromptArgument(name = "receipt", description = "Путь к файлу чека или фото", required = false, title = null)),
    ) { request ->
        val receipt = request.arguments?.get("receipt")
        val receiptLine = receipt?.let { "Чек: $it. " } ?: ""
        GetPromptResult(
            messages = listOf(
                textPrompt(
                    Role.User,
                    receiptLine + "Распознай позиции чека (сам, по фото/файлу). Вызови get_context и resolve для " +
                        "счёта оплаты и статей, затем get_receipt_hints по текстам позиций — при высокой " +
                        "уверенности выбирай статью из подсказки. Отправь чек через propose_receipt: пользователь " +
                        "увидит окно, поправит статьи и сохранит или отклонит. Затем вызови get_receipt_proposal с " +
                        "waitSeconds и посмотри, что он исправил, — это учится на будущее. Сам не записывай " +
                        "операции чека через add_operations.",
                ),
            ),
        )
    }

    server.addPrompt(
        name = "monthly_summary",
        description = "Итоги месяца: сводка операций и остатков за месяц.",
        arguments = listOf(PromptArgument(name = "month", description = "Месяц в формате YYYY-MM, по умолчанию текущий", required = false, title = null)),
    ) { request ->
        val month = request.arguments?.get("month")
        val monthLine = month?.let { "за $it" } ?: "за текущий месяц"
        GetPromptResult(
            messages = listOf(
                textPrompt(
                    Role.User,
                    "Собери итоги $monthLine: вызови get_spending_summary (без dateFrom/dateTo для текущего " +
                        "месяца, иначе с границами месяца $monthLine) для сводки по статьям, затем " +
                        "get_balances для остатков по счетам. Представь сводку по статьям и итоговые остатки.",
                ),
            ),
        )
    }

    server.addPrompt(
        name = "spending_on",
        description = "Сколько потрачено на …: сумма расходов по одной статье/контрагенту.",
        arguments = listOf(PromptArgument(name = "category", description = "Статья бюджета или контрагент", required = true, title = null)),
    ) { request ->
        val category = request.arguments?.get("category") ?: error("missing required argument: category")
        GetPromptResult(
            messages = listOf(
                textPrompt(
                    Role.User,
                    "Вызови resolve(\"$category\") по статьям бюджета (и, если не найдено, по контрагентам), " +
                        "затем list_operations с найденным id и посчитай сумму из поля aggregate. Ответь суммой " +
                        "и периодом, за который она посчитана.",
                ),
            ),
        )
    }
}

private fun registerAddOperationsTool(server: Server, session: AgentSession, mode: WriteMode, limits: WriteLimits, device: String) {
    server.addTool(
        name = "add_operations",
        description = "Add one or more operations, as drafts in «Входящие» or straight to the database, with " +
            "validation, per-operation/daily limits, a dry-run preview and idempotent replay. In write mode, a " +
            "client with the MCP elicitation capability is asked to confirm the batch before it is written.",
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
        val tool = AddOperationsTool(session)
        // Always ask for the dry-run preview first, even when the caller didn't request one: it is
        // the only way to learn what a real write would accept/reject without yet committing it, and
        // its shape also doubles as this call's own terminal answer whenever there is nothing further
        // to decide — see the branches below.
        val preview = tool.execute(drafts, mode, limits, dryRun = true, clientRequestId, device)
        // A "fresh" preview (dryRun:true echoed back) means this call actually reached the dry-run
        // branch of [AddOperationsTool.execute] just now. Its absence means [preview] is already a
        // terminal answer this handler must pass through unchanged instead of writing anything:
        // either a validation/read-only-mode error (returned before dryRun is even checked), or an
        // idempotent replay of an *already-applied* earlier call with this same [clientRequestId]
        // (`idempotency.find` short-circuits `execute` before dryRun is checked too) — asking the
        // human to confirm a write that already happened would be meaningless.
        val isFreshPreview = preview["dryRun"]?.jsonPrimitive?.booleanOrNull == true
        val accepted = preview["accepted"]?.jsonArray
        val result = when {
            dryRun || !isFreshPreview -> preview
            mode == WriteMode.Write && !accepted.isNullOrEmpty() && !this.confirmAddOperations(server, elicitationMessage(accepted)) ->
                declinedResult(mode, preview)
            else -> tool.execute(drafts, mode, limits, dryRun = false, clientRequestId, device)
        }
        result.toCallToolResult()
    }
}

/**
 * "Записать N операций на сумму …?" (dev_plan.md §12.14) — asks the connected client to confirm,
 * via MCP elicitation, before [registerAddOperationsTool] commits a write-mode `add_operations`
 * call. Returns `true` (meaning: proceed) outright when the client never declared the `elicitation`
 * capability during initialize — see this file's own top-of-file note for why that check has to
 * happen here rather than inside the SDK's `createElicitation` itself. The requested schema has no
 * properties: this is a plain accept/decline/cancel confirmation, not a form collecting input.
 */
private suspend fun ClientConnection.confirmAddOperations(server: Server, message: String): Boolean {
    val supportsElicitation = server.sessions[sessionId]?.clientCapabilities?.elicitation != null
    if (!supportsElicitation) return true
    val schema = ElicitRequestParams.RequestedSchema(properties = emptyMap(), required = emptyList())
    return createElicitation(message, schema).action == ElicitResult.Action.Accept
}

/** Sums [accepted]'s previews by currency name (dev_plan.md §12.14's own wording: "на сумму …") —
 * grouped, not blindly added across currencies, since a batch can mix them. */
private fun elicitationMessage(accepted: JsonArray): String {
    val sums = LinkedHashMap<String, BigDecimal>()
    for (entry in accepted) {
        val entryPreview = entry.jsonObject.getValue("preview").jsonObject
        val amount = entryPreview["amount"]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal) ?: BigDecimal.ZERO
        val currency = entryPreview["currency"]?.jsonPrimitive?.contentOrNull ?: "?"
        sums.merge(currency, amount, BigDecimal::add)
    }
    val total = sums.entries.joinToString("; ") { (currency, sum) -> "$sum $currency" }
    return "Записать ${accepted.size} операций на сумму $total?"
}

/** The declined-by-the-human counterpart of a successful `add_operations` write result — mirrors
 * [AddOperationsTool]'s own JSON shape closely enough for a caller to show the same accepted/rejected
 * preview it already had, but deliberately not recorded in [kalita.agent.api.IdempotencyLog]: unlike
 * a validation failure, a decline is not a property of the request itself, so a retry with the same
 * `clientRequestId` (e.g. after the human changes their mind) must be free to ask again. */
private fun declinedResult(mode: WriteMode, preview: JsonObject): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", "declined_by_user")
    put("mode", mode.name.lowercase())
    put("accepted", preview.getValue("accepted"))
    put("rejected", preview.getValue("rejected"))
}

/**
 * `get_receipt_hints` and `get_receipt_proposal` (dev_plan.md §12.19): read-only, so available in
 * every mode. `get_receipt_proposal` can wait (`waitSeconds`, at most [MAX_RECEIPT_WAIT_SECONDS]) for the
 * person to decide while the receipt window is open, so the agent gets the feedback in the same turn.
 */
private fun registerReceiptReadTools(server: Server, session: AgentSession) {
    server.addTool(
        name = "get_receipt_hints",
        description = "What earlier receipts taught: for each receipt line text, the budget item the person " +
            "finally chose for the same or a similar line (with evidence count and confidence). Call it BEFORE " +
            "propose_receipt and prefer a high-confidence hint over a fresh guess.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("texts", arraySchema("string"))
                put("merchant", typeSchema("string"))
            },
            required = listOf("texts"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val texts = args.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
        GetReceiptHintsTool(session).execute(texts, args["merchant"]?.jsonPrimitive?.contentOrNull).toCallToolResult()
    }

    server.addTool(
        name = "get_receipt_proposal",
        description = "The person's decision on a receipt sent with propose_receipt: status pending/saved/rejected " +
            "and, once saved, per line whether they confirmed your budget item, corrected it, added the line or " +
            "removed it. Learn from the corrections. waitSeconds (0–$MAX_RECEIPT_WAIT_SECONDS) waits while it is still pending.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("proposalId", typeSchema("string"))
                put("waitSeconds", typeSchema("integer"))
            },
            required = listOf("proposalId"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        val args = request.arguments ?: JsonObject(emptyMap())
        val id = args.getValue("proposalId").jsonPrimitive.content
        val waitMillis = (args["waitSeconds"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, MAX_RECEIPT_WAIT_SECONDS) * 1000L
        val deadline = System.nanoTime() + waitMillis * 1_000_000L
        var result: JsonObject
        while (true) {
            session.refresh()
            result = GetReceiptProposalTool(session).execute(id)
            val pending = result.ok() && result["status"]?.jsonPrimitive?.contentOrNull == "pending"
            if (!pending || System.nanoTime() >= deadline) break
            kotlinx.coroutines.delay(1000)
        }
        result.toCallToolResult()
    }
}

private const val MAX_RECEIPT_WAIT_SECONDS = 120

/**
 * `propose_receipt` (dev_plan.md §12.19): shows the person a window with the receipt lines, the
 * budget items the agent picked, the total and the paying account; nothing is written until they
 * save. Registered in draft and write mode alike — the review itself is the confirmation, so no
 * elicitation is asked on top.
 */
private fun registerProposeReceiptTool(server: Server, session: AgentSession, mode: WriteMode, device: String) {
    server.addTool(
        name = "propose_receipt",
        description = "Send a recognized receipt to the person for review instead of writing operations yourself. " +
            "The Kalita window shows the lines with your budget items, the sum and the paying account; the person " +
            "fixes the items and saves or rejects. Use for any receipt photo/scan. Resolve ids first (get_context, " +
            "resolve) and call get_receipt_hints for the line texts. Then call get_receipt_proposal (waitSeconds) " +
            "to learn what was saved. Lines: text as printed, amount as printed (positive), budgetItemId.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("merchant", typeSchema("string"))
                put("date", typeSchema("string"))
                put("currencyId", typeSchema("integer"))
                put("accountFromId", typeSchema("integer"))
                put("total", typeSchema("string"))
                put("note", typeSchema("string"))
                put("imagePath", typeSchema("string"))
                put(
                    "lines",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("text", typeSchema("string"))
                                        put("amount", typeSchema("string"))
                                        put("budgetItemId", typeSchema("integer"))
                                    },
                                )
                                put("required", JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("amount"))))
                            },
                        )
                    },
                )
                put("clientRequestId", typeSchema("string"))
            },
            required = listOf("lines", "clientRequestId"),
        ),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val clientRequestId = args.getValue("clientRequestId").jsonPrimitive.content
        ProposeReceiptTool(session).execute(ReceiptProposalInput.fromJson(args), mode, clientRequestId, device).toCallToolResult()
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

/** `update_operation`/`delete_operation`/`restore_operation`/`add_dictionary_item`/`undo_changes`
 * (dev_plan.md §12.13-§12.14): write-mode-only, no `draft` fallback — see [buildMcpServer]'s own
 * KDoc. Every handler still re-checks `clientRequestId` idempotency itself (the tool classes do,
 * not this registration code), same as `add_operations`. */
private fun registerWriteOnlyTools(server: Server, session: AgentSession, device: String) {
    server.addTool(
        name = "update_operation",
        description = "Replace an existing operation's fields wholesale.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("id", typeSchema("integer"))
                put(
                    "draft",
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
                    },
                )
                put("clientRequestId", typeSchema("string"))
            },
            required = listOf("id", "draft", "clientRequestId"),
        ),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val id = args.getValue("id").jsonPrimitive.long
        val draft = decodeMcpDraft(args.getValue("draft").jsonObject)
        val clientRequestId = args.getValue("clientRequestId").jsonPrimitive.content
        UpdateOperationTool(session).execute(id, draft, WriteMode.Write, clientRequestId, device).toCallToolResult()
    }

    server.addTool(
        name = "delete_operation",
        description = "Soft-delete one operation.",
        inputSchema = ToolSchema(
            properties = buildJsonObject { put("id", typeSchema("integer")); put("clientRequestId", typeSchema("string")) },
            required = listOf("id", "clientRequestId"),
        ),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val id = args.getValue("id").jsonPrimitive.long
        val clientRequestId = args.getValue("clientRequestId").jsonPrimitive.content
        DeleteOperationTool(session).execute(id, WriteMode.Write, clientRequestId, device).toCallToolResult()
    }

    server.addTool(
        name = "restore_operation",
        description = "Restore one soft-deleted operation.",
        inputSchema = ToolSchema(
            properties = buildJsonObject { put("id", typeSchema("integer")); put("clientRequestId", typeSchema("string")) },
            required = listOf("id", "clientRequestId"),
        ),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val id = args.getValue("id").jsonPrimitive.long
        val clientRequestId = args.getValue("clientRequestId").jsonPrimitive.content
        RestoreOperationTool(session).execute(id, WriteMode.Write, clientRequestId, device).toCallToolResult()
    }

    server.addTool(
        name = "add_dictionary_item",
        description = "Add a new account/budget item/counterparty/currency, by explicit call only — " +
            "never a side effect of add_operations.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("kind", buildJsonObject { put("type", "string"); put("enum", JsonArray(DICTIONARY_KIND_WORDS.map(::JsonPrimitive))) })
                put("name", typeSchema("string"))
                put("parentId", typeSchema("integer"))
                put("cbId", typeSchema("string"))
                put("clientRequestId", typeSchema("string"))
            },
            required = listOf("kind", "name", "clientRequestId"),
        ),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val kind = parseDictionaryKindMcp(args.getValue("kind").jsonPrimitive.content)
        val name = args.getValue("name").jsonPrimitive.content
        val parentId = args["parentId"]?.jsonPrimitive?.longOrNull
        val cbId = args["cbId"]?.jsonPrimitive?.contentOrNull
        val clientRequestId = args.getValue("clientRequestId").jsonPrimitive.content
        AddDictionaryItemTool(session).execute(kind, name, parentId, cbId, WriteMode.Write, clientRequestId, device).toCallToolResult()
    }

    server.addTool(
        name = "undo_changes",
        description = "Reverse one change set this same device created (a changeSetId returned by an earlier write tool call).",
        inputSchema = ToolSchema(
            properties = buildJsonObject { put("changeSetId", typeSchema("string")); put("clientRequestId", typeSchema("string")) },
            required = listOf("changeSetId", "clientRequestId"),
        ),
    ) { request ->
        session.refresh()
        val args = request.arguments ?: JsonObject(emptyMap())
        val changeSetId = args.getValue("changeSetId").jsonPrimitive.content
        val clientRequestId = args.getValue("clientRequestId").jsonPrimitive.content
        UndoChangesTool(session).execute(changeSetId, WriteMode.Write, clientRequestId, device).toCallToolResult()
    }
}
