// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One tool's model-facing description (dev_plan.md §12.12: "name, description for the model, JSON
 * schema of input/output, access level"). This slice keeps the shape informal rather than real JSON
 * Schema documents — `kalita schema` only needs to be a short, structured description an agent (or
 * a human skimming it) can read, not a machine-validated contract yet.
 */
data class ToolDescriptor(val name: String, val description: String, val access: String, val parameters: List<String>)

/** Backs `kalita schema` (FR-AG1) — the same descriptors a future `kalita mcp` would expose as MCP tools. */
object ToolCatalog {
    val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            "get_context",
            "Today's date, base currency, live dictionaries (accounts, currencies, counterparties, " +
                "budget tree, operation types).",
            "read",
            listOf("today (optional ISO date)"),
        ),
        ToolDescriptor(
            "resolve",
            "Name lookup across one or more dictionaries with a match confidence, for disambiguation " +
                "before calling a write tool.",
            "read",
            listOf("query (string)", "kinds (account|budget_item|currency|counterparty|operation_type, one or more)", "limit (optional)"),
        ),
        ToolDescriptor(
            "list_operations",
            "Journal search by date/account/budget item/currency/counterparty/type/amount/comment, " +
                "paginated, with aggregate sums.",
            "read",
            listOf("dateFrom", "dateTo", "budgetItemIds", "accountIds", "currencyIds", "counterpartyIds", "typeIds", "amountMin", "amountMax", "commentSubstring", "offset", "limit"),
        ),
        ToolDescriptor(
            "get_balances",
            "Account balances per currency and the ИТОГО total in the reduction currency.",
            "read",
            listOf("reductionCurrencyId (optional)"),
        ),
        ToolDescriptor(
            "add_operations",
            "Add one or more operations, as drafts in «Входящие» or straight to the database, with " +
                "validation, per-operation/daily limits, a dry-run preview and idempotent replay.",
            "draft/write",
            listOf("drafts (list of operation drafts, ids already resolved)", "mode", "limits", "dryRun", "clientRequestId", "device"),
        ),
        ToolDescriptor("list_inbox", "Pending drafts awaiting a human's confirmation.", "read", emptyList()),
        ToolDescriptor("approve_inbox", "Commit one pending draft straight to the database.", "write", listOf("inboxId")),
        ToolDescriptor("reject_inbox", "Mark one pending draft as rejected, without touching the database.", "write", listOf("inboxId")),
        ToolDescriptor(
            "update_operation",
            "Replace an existing operation's fields wholesale. Write mode only, no draft fallback.",
            "write",
            listOf("id", "draft (operation fields, ids already resolved)", "clientRequestId", "device"),
        ),
        ToolDescriptor(
            "delete_operation",
            "Soft-delete one operation. Write mode only, no draft fallback.",
            "write",
            listOf("id", "clientRequestId", "device"),
        ),
        ToolDescriptor(
            "restore_operation",
            "Restore one soft-deleted operation. Write mode only, no draft fallback.",
            "write",
            listOf("id", "clientRequestId", "device"),
        ),
        ToolDescriptor(
            "add_dictionary_item",
            "Add a new account/budget item/counterparty/currency, by explicit call only — never a " +
                "side effect of add_operations. Write mode only, no draft fallback.",
            "write",
            listOf("kind (account|budget_item|counterparty|currency)", "name", "parentId (budget_item only)", "cbId (currency only)", "clientRequestId", "device"),
        ),
    )

    fun toJson(): JsonArray = JsonArray(
        tools.map { t ->
            buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("access", t.access)
                put("parameters", JsonArray(t.parameters.map { JsonPrimitive(it) }))
            }
        },
    )
}
