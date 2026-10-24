// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * `get_context` (dev_plan.md §12.13): today's date, base currency, the live dictionaries and the
 * budget tree an agent needs before calling [ResolveTool]/[AddOperationsTool].
 *
 * Omitted from this slice: "last used values" (§12.13's own description) — that needs more
 * history-tracking machinery (most-recently-used accounts/currencies per operation kind) than
 * exists yet; [kalita.core.OperationRules.defaults] only covers the desktop form's own needs.
 */
class GetContextTool(private val dataset: Dataset) {
    fun execute(today: LocalDate = LocalDate.now()): JsonObject {
        val baseCurrencyId = dataset.detectBaseCurrency()
        val baseCurrency = dataset.currency(baseCurrencyId)
        return buildJsonObject {
            put("today", today.toString())
            put("baseCurrency", baseCurrency?.let { idName(it.id, it.name) } ?: JsonNull)
            put("accounts", JsonArray(dataset.accounts.filter { !it.deleted }.map { idName(it.id, it.name) }))
            put("currencies", JsonArray(dataset.currencies.filter { !it.deleted }.map { idName(it.id, it.name) }))
            put("counterparties", JsonArray(dataset.counterparties.filter { !it.deleted }.map { idName(it.id, it.name) }))
            put(
                "budgetItems",
                JsonArray(
                    dataset.budgetItems.filter { !it.deleted }.map { item ->
                        buildJsonObject {
                            put("id", item.id)
                            put("name", item.name)
                            put("parentId", item.parentId)
                        }
                    },
                ),
            )
            put(
                "operationTypes",
                JsonArray(
                    dataset.operationTypes.filter { !it.deleted }.map { type ->
                        buildJsonObject {
                            put("id", type.id)
                            put("name", type.name)
                            put("kind", type.kind?.name)
                        }
                    },
                ),
            )
        }
    }
}
