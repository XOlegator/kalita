// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import kalita.core.Dataset
import kalita.plugin.api.Categorizer
import kalita.plugin.api.CategorizerSuggestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * One user rule of dev_plan.md §11.3 step 5 («правила пользователя по описанию/MCC/продавцу»):
 * when [field] of a bank line matches [pattern] by [match], file the new operation under
 * [budgetItemId] and/or [counterpartyId]. Text comparison ignores case and repeated spaces
 * (the same normalization as [HistoryCategorizer]).
 */
data class CategoryRule(
    val field: Field,
    val match: Match,
    val pattern: String,
    val budgetItemId: Long?,
    val counterpartyId: Long?,
) {
    enum class Field {
        /** The bank line's description (falls back here when a statement has no separate merchant). */
        DESCRIPTION,

        /** [kalita.plugin.api.BankTransaction.merchant], or the description's text before the first comma when the statement has none. */
        MERCHANT,

        /** The merchant category code, compared as written (`5411`). */
        MCC,
    }

    enum class Match { CONTAINS, STARTS_WITH, EQUALS }

    /** A rule with nothing to match or nothing to set is never applied — the editor refuses to save one, this guards hand-edited files. */
    val usable: Boolean get() = pattern.isNotBlank() && (budgetItemId != null || counterpartyId != null)

    fun matches(description: String, merchant: String?, mcc: String?): Boolean {
        if (!usable) return false
        val subject = when (field) {
            Field.DESCRIPTION -> HistoryCategorizer.normalize(description)
            Field.MERCHANT -> merchant?.let(HistoryCategorizer::normalize)?.takeIf { it.isNotEmpty() }
                ?: HistoryCategorizer.merchantOf(HistoryCategorizer.normalize(description))
            Field.MCC -> mcc?.trim() ?: return false
        }
        val p = if (field == Field.MCC) pattern.trim() else HistoryCategorizer.normalize(pattern)
        return when (match) {
            Match.CONTAINS -> subject.contains(p)
            Match.STARTS_WITH -> subject.startsWith(p)
            Match.EQUALS -> subject == p
        }
    }

    companion object {
        /**
         * The rule the import screen offers to remember after the user filed a new bank line by
         * hand: the merchant part of its description (so other branches and later dates of the
         * same shop match too), «содержит».
         */
        fun fromBankLine(description: String, merchant: String?, budgetItemId: Long?, counterpartyId: Long?): CategoryRule {
            val key = merchant?.let(HistoryCategorizer::normalize)?.takeIf { it.isNotEmpty() }
                ?: HistoryCategorizer.merchantOf(HistoryCategorizer.normalize(description))
            return CategoryRule(Field.MERCHANT, Match.CONTAINS, key, budgetItemId, counterpartyId)
        }
    }
}

/**
 * The user's rules, in priority order (the first match wins), kept next to the database as
 * `<dataDirectory>/category-rules.json` — the same place as the bank mappings (dev_plan.md §9.4,
 * §10.7), so the rules travel with a shared database through Nextcloud.
 */
class CategoryRuleStore(dataDirectory: File) {
    private val file = File(dataDirectory, "category-rules.json")

    fun read(): List<CategoryRule> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val root = Json.parseToJsonElement(text).jsonObject
        return root["rules"]?.jsonArray?.mapNotNull { runCatching { decode(it.jsonObject) }.getOrNull() }.orEmpty()
    }

    fun write(rules: List<CategoryRule>) {
        val json = buildJsonObject { put("rules", JsonArray(rules.map(::encode))) }
        atomicWrite(file, json.toString())
    }

    /**
     * Adds [rule] at the top (a rule made just now is the most specific thing the user said) and
     * drops an older rule on the same field and pattern — remembering the same shop again
     * re-files it rather than leaving a stale duplicate further down.
     */
    fun add(rule: CategoryRule) {
        fun normalized(r: CategoryRule) = if (r.field == CategoryRule.Field.MCC) r.pattern.trim() else HistoryCategorizer.normalize(r.pattern)
        val same = { r: CategoryRule -> r.field == rule.field && r.match == rule.match && normalized(r) == normalized(rule) }
        write(listOf(rule) + read().filterNot(same))
    }

    private fun encode(r: CategoryRule): JsonObject = buildJsonObject {
        put("field", r.field.name)
        put("match", r.match.name)
        put("pattern", r.pattern)
        put("budgetItem", r.budgetItemId?.let(::JsonPrimitive) ?: JsonNull)
        put("counterparty", r.counterpartyId?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun decode(o: JsonObject) = CategoryRule(
        field = CategoryRule.Field.valueOf(o.getValue("field").jsonPrimitive.content),
        match = CategoryRule.Match.valueOf(o.getValue("match").jsonPrimitive.content),
        pattern = o.getValue("pattern").jsonPrimitive.let { require(it.isString); it.content },
        budgetItemId = optionalId(o, "budgetItem"),
        counterpartyId = optionalId(o, "counterparty"),
    )

    private fun optionalId(o: JsonObject, key: String): Long? {
        val value = o[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value.jsonPrimitive
        require(!primitive.isString)
        return requireNotNull(primitive.longOrNull)
    }
}

/**
 * The «rules» tier of dev_plan.md §11.3 step 5, consulted before [HistoryCategorizer]. A rule
 * pointing at a budget item or counterparty that has since been deleted from [dataset] is
 * skipped rather than proposing a dead reference.
 */
class RuleCategorizer(private val rules: List<CategoryRule>, private val dataset: Dataset? = null) : Categorizer {
    override suspend fun suggest(description: String, merchant: String?, mcc: String?): List<CategorizerSuggestion> {
        val rule = rules.firstOrNull { it.matches(description, merchant, mcc) && alive(it) } ?: return emptyList()
        return listOf(CategorizerSuggestion(rule.budgetItemId, null, rule.counterpartyId, 1.0f))
    }

    private fun alive(rule: CategoryRule): Boolean {
        val ds = dataset ?: return true
        val itemOk = rule.budgetItemId == null || ds.budgetItems.any { it.id == rule.budgetItemId && !it.deleted }
        val whoOk = rule.counterpartyId == null || ds.counterparties.any { it.id == rule.counterpartyId && !it.deleted }
        return itemOk && whoOk
    }
}
