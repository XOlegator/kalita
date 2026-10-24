// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.ProgramResult
import kalita.agent.api.ResolveKind
import kalita.agent.api.ResolveTool
import kalita.core.BudgetTree
import kalita.core.Dataset
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.core.OperationRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.PrintStream
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The fields of one `op add` invocation, however they arrived — command-line flags for a single
 * operation, or one element of `--batch <file.json>`'s JSON array. Both paths build an
 * [OperationDraft] through [buildDraft] (dev_plan.md §12.17's own worked example: the CLI resolves
 * names via [ResolveTool], the underlying tool never does).
 */
internal data class OpAddParams(
    val type: String,
    val amount: BigDecimal,
    val category: String,
    val account: String,
    val accountTo: String?,
    val date: String,
    val comment: String?,
)

internal fun OpAddParams.toJsonElement(): JsonObject = buildJsonObject {
    put("type", type)
    put("amount", amount.toPlainString())
    put("category", category)
    put("account", account)
    put("accountTo", accountTo)
    put("date", date)
    put("comment", comment)
}

internal fun parseOpAddParams(o: JsonObject): OpAddParams = OpAddParams(
    type = o.getValue("type").jsonPrimitive.content,
    amount = BigDecimal(o.getValue("amount").jsonPrimitive.content),
    category = o.getValue("category").jsonPrimitive.content,
    account = o.getValue("account").jsonPrimitive.content,
    accountTo = o["accountTo"]?.jsonPrimitive?.contentOrNull,
    date = o.getValue("date").jsonPrimitive.content,
    comment = o["comment"]?.jsonPrimitive?.contentOrNull,
)

/**
 * `--type expense|income|transfer` is the operation's *kind*, not a dictionary name — unlike
 * `--category`/`--account`/`--currency`, resolving it is not a [ResolveTool] name lookup (nothing
 * in the database is literally named "expense"). Instead: the kind narrows the dataset's
 * [kalita.core.OperationType] rows, and the already-resolved category narrows further by budget
 * subtree (`kalita.core.OperationRules` ties a type to the budget items under its own root) — the
 * same relationship the desktop form enforces. Ambiguous or empty narrows the same way a
 * [ResolveTool] miss would: print candidates, exit non-zero.
 */
internal fun resolveOperationTypeOrExit(
    dataset: Dataset,
    typeWord: String,
    budgetItemId: Long,
    asJson: Boolean,
    out: PrintStream,
    err: PrintStream,
): Long {
    val kind = when (typeWord.lowercase()) {
        "expense" -> OperationKind.Expense
        "income" -> OperationKind.Income
        "transfer" -> OperationKind.Transfer
        else -> {
            err.println("unknown --type: $typeWord (expected expense|income|transfer)")
            throw ProgramResult(2)
        }
    }
    val tree = BudgetTree(dataset.budgetItems)
    val candidates = dataset.operationTypes.filter { type ->
        val rootId = type.budgetRootId
        !type.deleted && type.kind == kind && (rootId == null || budgetItemId in tree.withDescendants(listOf(rootId)))
    }
    if (candidates.size == 1) return candidates.single().id
    val candidatesJson = JsonArray(
        candidates.map { t ->
            buildJsonObject {
                put("kind", "OperationType")
                put("id", t.id)
                put("name", t.name)
                put("confidence", 1.0)
            }
        },
    )
    printResolveFailure("тип операции ($typeWord)", typeWord, candidatesJson, asJson, out, err)
    throw ProgramResult(1)
}

/** Builds one [OperationDraft], resolving every name in [params] via [resolveTool] (and the
 * operation type via [resolveOperationTypeOrExit]); exits non-zero on the first unresolved name. */
internal fun buildDraft(
    dataset: Dataset,
    resolveTool: ResolveTool,
    params: OpAddParams,
    today: LocalDate,
    asJson: Boolean,
    out: PrintStream,
    err: PrintStream,
): OperationDraft {
    val date = if (params.date.equals("today", ignoreCase = true)) today else LocalDate.parse(params.date)
    val budgetItemId = resolveIdOrExit(resolveTool, params.category, ResolveKind.BudgetItem, "статью", asJson, out, err)
    val accountFromId = resolveIdOrExit(resolveTool, params.account, ResolveKind.Account, "счёт", asJson, out, err)
    val accountToId = params.accountTo?.let { resolveIdOrExit(resolveTool, it, ResolveKind.Account, "счёт назначения", asJson, out, err) }
    val typeId = resolveOperationTypeOrExit(dataset, params.type, budgetItemId, asJson, out, err)
    val kind = dataset.operationType(typeId)?.kind
    val currencyId = OperationRules(dataset).defaults(kind, today).currencyId
    // Income (§5.1) credits accountTo only; expense debits accountFrom only; the single-flag CLI
    // shape only offers --account/--account-to, so an income draft's one --account names the target.
    return OperationDraft(
        date = date,
        amount = params.amount,
        currencyId = currencyId,
        budgetItemId = budgetItemId,
        typeId = typeId,
        comment = params.comment,
        accountFrom = if (kind == OperationKind.Income) null else accountFromId,
        accountTo = if (kind == OperationKind.Income) accountFromId else accountToId,
    )
}
