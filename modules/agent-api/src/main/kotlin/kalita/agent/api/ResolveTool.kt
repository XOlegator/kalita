// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which dictionary a [ResolveTool] query searches. */
enum class ResolveKind { Account, BudgetItem, Currency, Counterparty, OperationType }

/**
 * `resolve` (dev_plan.md §12.13): turns a name an agent (or the person it's acting for) typed into
 * candidate dictionary rows with a confidence, so the caller can either use the top one or show the
 * person a disambiguation list.
 *
 * Matching is deliberately simple — case-insensitive exact match first (confidence 1.0), then a
 * case-insensitive substring match (confidence scaled by how much of the candidate's name the query
 * covers), then nothing. Full fuzzy/typo-tolerant matching (e.g. Levenshtein) is not implemented —
 * exact+substring is an honest, useful MVP for this slice, not the final word on "нечёткий поиск".
 */
class ResolveTool(private val dataset: Dataset) {
    fun execute(query: String, kinds: Set<ResolveKind>, limit: Int = 10): JsonObject {
        val candidates = kinds.flatMap(::namesOf)
            .mapNotNull { (kind, id, name) -> name?.let { score(query, it) }?.let { Candidate(kind, id, name, it) } }
            .sortedByDescending { it.confidence }
            .take(limit.coerceIn(1, MAX_LIMIT))
        return buildJsonObject {
            put("query", query)
            put(
                "candidates",
                JsonArray(
                    candidates.map { c ->
                        buildJsonObject {
                            put("kind", c.kind.name)
                            put("id", c.id)
                            put("name", c.name)
                            put("confidence", c.confidence)
                        }
                    },
                ),
            )
        }
    }

    private data class Candidate(val kind: ResolveKind, val id: Long, val name: String, val confidence: Double)

    private fun namesOf(kind: ResolveKind): List<Triple<ResolveKind, Long, String?>> = when (kind) {
        ResolveKind.Account -> dataset.accounts.filter { !it.deleted }.map { Triple(kind, it.id, it.name) }
        ResolveKind.BudgetItem -> dataset.budgetItems.filter { !it.deleted }.map { Triple(kind, it.id, it.name) }
        ResolveKind.Currency -> dataset.currencies.filter { !it.deleted }.map { Triple(kind, it.id, it.name) }
        ResolveKind.Counterparty -> dataset.counterparties.filter { !it.deleted }.map { Triple(kind, it.id, it.name) }
        ResolveKind.OperationType -> dataset.operationTypes.filter { !it.deleted }.map { Triple(kind, it.id, it.name) }
    }

    /** `null` for no match; 1.0 exact; otherwise `query.length / name.length`, capped below 1.0. */
    private fun score(query: String, name: String): Double? {
        if (query.isBlank()) return null
        if (name.equals(query, ignoreCase = true)) return 1.0
        if (!name.contains(query, ignoreCase = true)) return null
        return (query.length.toDouble() / name.length).coerceIn(0.05, 0.9)
    }

    private companion object {
        const val MAX_LIMIT = 10
    }
}
