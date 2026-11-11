// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import kalita.core.Dataset
import kalita.plugin.api.Categorizer
import kalita.plugin.api.CategorizerSuggestion

/**
 * The "history" tier of dev_plan.md §11.3 step 5 / §12.4: what did the user file previous
 * operations with the same comment under? Exact comment first (the shell writes the bank line's
 * description into the comment of operations it creates, so a second import of the same
 * merchant finds the first import's choice), then the merchant part alone (text before the
 * first comma: `"MAXI 123, BEOGRAD"` → `"MAXI 123"`), so a different branch of the same chain
 * still matches. Confidence tells the two tiers apart; a plugin [Categorizer] (user rules) is
 * consulted before this one by [CategorizerChain].
 */
class HistoryCategorizer(private val dataset: Dataset) : Categorizer {
    private data class Choice(val budgetItemId: Long?, val counterpartyId: Long?)

    private val byComment: Map<String, Choice> by lazy { pick { it } }
    private val byMerchant: Map<String, Choice> by lazy { pick { merchantOf(it) } }

    private fun pick(keyOf: (String) -> String): Map<String, Choice> =
        dataset.operations.asSequence()
            .filter { !it.deleted && it.budgetItemId != null && !it.comment.isNullOrBlank() }
            .groupBy { keyOf(normalize(it.comment!!)) }
            .mapValues { (_, ops) ->
                ops.groupingBy { Choice(it.budgetItemId, it.counterpartyId) }.eachCount().maxBy { it.value }.key
            }

    override suspend fun suggest(description: String, merchant: String?, mcc: String?): List<CategorizerSuggestion> {
        val key = normalize(description)
        byComment[key]?.let { return listOf(CategorizerSuggestion(it.budgetItemId, null, it.counterpartyId, 0.9f)) }
        val merchantKey = merchant?.let(::normalize) ?: merchantOf(key)
        byMerchant[merchantKey]?.let { return listOf(CategorizerSuggestion(it.budgetItemId, null, it.counterpartyId, 0.6f)) }
        return emptyList()
    }

    companion object {
        fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

        fun merchantOf(normalized: String): String = normalized.substringBefore(',').trim()
    }
}

/** First suggestion wins, in the order given (plugins' rules first, then history). */
class CategorizerChain(private val categorizers: List<Categorizer>) : Categorizer {
    override suspend fun suggest(description: String, merchant: String?, mcc: String?): List<CategorizerSuggestion> {
        for (c in categorizers) {
            val s = c.suggest(description, merchant, mcc)
            if (s.isNotEmpty()) return s
        }
        return emptyList()
    }
}
