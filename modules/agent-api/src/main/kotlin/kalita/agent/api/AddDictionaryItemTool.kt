// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.storage.api.SubmitResult
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which flat dictionary [AddDictionaryItemTool] creates a row in. Operation types are not here —
 * they are fixed system data (dev_plan.md §4.3), never agent- or user-created. */
enum class DictionaryKind { Account, BudgetItem, Counterparty, Currency }

/**
 * `add_dictionary_item` (dev_plan.md §12.13-§12.14): a new account/budget item/counterparty/
 * currency — "only by an explicit call, never 'along the way'" of `add_operations`, and write mode
 * only (no `draft` fallback, same as [UpdateOperationTool] and friends).
 */
class AddDictionaryItemTool(private val session: AgentSession) {
    fun execute(
        kind: DictionaryKind,
        name: String,
        parentId: Long? = null,
        cbId: String? = null,
        mode: WriteMode,
        clientRequestId: String,
        device: String,
    ): JsonObject {
        val idempotency = IdempotencyLog(session.dataDirectory)
        idempotency.find(clientRequestId)?.let { return it }

        requireWriteMode(mode)?.let { return it }
        if (name.isBlank()) return errorResult("missing name")

        val dataset = session.dataset
        if (kind == DictionaryKind.BudgetItem && parentId != null) {
            val parent = dataset.budgetItem(parentId)
            if (parent == null || parent.deleted) return errorResult("no such parent budget item: $parentId")
        }

        val table = tableFor(kind)
        val values = valuesFor(kind, name, parentId, cbId)
        var tempKey: TempKey? = null
        val result = session.submit("Агент ($device): добавлен(а) ${nounFor(kind)} «$name»", device) { builder ->
            val key = builder.nextTempKey()
            tempKey = key
            builder.insert(table, key, values)
        }
        return when (result) {
            is SubmitResult.Applied -> {
                val id = result.tempKeys.getValue(tempKey!!)
                val json = buildJsonObject {
                    put("ok", true)
                    put("kind", kind.name)
                    put("id", id)
                    put("name", name)
                }
                idempotency.record(clientRequestId, json)
                json
            }
            is SubmitResult.Conflicts -> errorResult("conflicts: ${result.conflicts.size} field(s) changed since read")
            is SubmitResult.Failed -> errorResult(result.reason)
        }
    }

    private fun tableFor(kind: DictionaryKind): Table = when (kind) {
        DictionaryKind.Account -> Table.Accounts
        DictionaryKind.BudgetItem -> Table.BudgetItems
        DictionaryKind.Counterparty -> Table.People
        DictionaryKind.Currency -> Table.Currencies
    }

    private fun valuesFor(kind: DictionaryKind, name: String, parentId: Long?, cbId: String?): Map<String, Any?> = when (kind) {
        DictionaryKind.Account -> mapOf("Name" to name)
        DictionaryKind.BudgetItem -> mapOf("Name" to name, "Parent_ID" to parentId)
        DictionaryKind.Counterparty -> mapOf("Name" to name)
        DictionaryKind.Currency -> mapOf("Name" to name, "CB_ID" to cbId)
    }

    private fun nounFor(kind: DictionaryKind): String = when (kind) {
        DictionaryKind.Account -> "счёт"
        DictionaryKind.BudgetItem -> "статья"
        DictionaryKind.Counterparty -> "контрагент"
        DictionaryKind.Currency -> "валюта"
    }
}
