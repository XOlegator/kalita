// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A rule matches an exact merchant and/or contained line text. Both conditions must match when present. */
data class ReceiptCategoryRule(val merchant: String?, val contains: String?, val budgetItemId: Long)

/** A user dictionary entry matches contained line text, independently of the merchant. */
data class ReceiptDictionaryEntry(val text: String, val budgetItemId: Long)

/** A built-in phrase, its immutable default meaning, and its effective meaning (null means disabled). */
data class BuiltInReceiptDictionaryEntry(val text: String, val defaultMeaning: String, val meaning: String?)

/** UI management API. Replace each complete ordered list; data is stored outside Data.mdb.
 * Rules are evaluated in list order, before learning and model guesses. Dictionary entries are a fallback.
 * Invalid or no-longer-active targets are ignored at suggestion time.
 */
class ReceiptCategorizationStore(dataDirectory: File) {
    private val file = File(dataDirectory, "receipt-categorization.json")

    private fun read() = if (file.isFile) Json.parseToJsonElement(file.readText()).jsonObject else buildJsonObject { }

    fun rules(): List<ReceiptCategoryRule> = read()["rules"]?.jsonArray.orEmpty().map {
        val o = it.jsonObject
        ReceiptCategoryRule(o["merchant"]?.jsonPrimitive?.contentOrNull, o["contains"]?.jsonPrimitive?.contentOrNull, o.getValue("budgetItemId").jsonPrimitive.long)
    }

    fun dictionary(): List<ReceiptDictionaryEntry> = read()["dictionary"]?.jsonArray.orEmpty().map {
        val o = it.jsonObject
        ReceiptDictionaryEntry(o.getValue("text").jsonPrimitive.content, o.getValue("budgetItemId").jsonPrimitive.long)
    }

    fun builtInDictionary(): List<BuiltInReceiptDictionaryEntry> {
        val overrides = read()["builtInDictionary"]?.jsonObject.orEmpty()
        return SerbianReceiptDictionary.vocabulary.map { (text, meaning) ->
            BuiltInReceiptDictionaryEntry(text, meaning, if (text in overrides) overrides.getValue(text).jsonPrimitive.contentOrNull else meaning)
        }
    }

    /** Set a built-in phrase's meaning, or disable it with null. */
    fun setBuiltInDictionaryEntry(text: String, meaning: String?) {
        require(text in SerbianReceiptDictionary.vocabulary)
        require(meaning == null || meaning.isNotBlank())
        val current = read()
        val overrides = current["builtInDictionary"]?.jsonObject.orEmpty()
        writeObject(buildJsonObject {
            current.forEach { (key, value) -> if (key != "builtInDictionary") put(key, value) }
            put("builtInDictionary", buildJsonObject {
                overrides.forEach { (key, value) -> if (key != text) put(key, value) }
                put(text, meaning)
            })
        })
    }

    /** Restore the original meaning of a built-in phrase. */
    fun resetBuiltInDictionaryEntry(text: String) {
        require(text in SerbianReceiptDictionary.vocabulary)
        val current = read()
        val overrides = current["builtInDictionary"]?.jsonObject.orEmpty()
        writeObject(buildJsonObject {
            current.forEach { (key, value) -> if (key != "builtInDictionary") put(key, value) }
            put("builtInDictionary", buildJsonObject { overrides.forEach { (key, value) -> if (key != text) put(key, value) } })
        })
    }

    fun replaceRules(rules: List<ReceiptCategoryRule>) {
        require(rules.all { (it.merchant == null || key(it.merchant).isNotEmpty()) && (it.contains == null || key(it.contains).isNotEmpty()) && (it.merchant != null || it.contains != null) })
        write(rules, dictionary())
    }

    fun replaceDictionary(entries: List<ReceiptDictionaryEntry>) {
        require(entries.all { key(it.text).isNotEmpty() })
        write(rules(), entries)
    }

    private fun write(rules: List<ReceiptCategoryRule>, entries: List<ReceiptDictionaryEntry>) {
        val json = buildJsonObject {
            read().forEach { (key, value) -> if (key != "rules" && key != "dictionary") put(key, value) }
            put("rules", JsonArray(rules.map { r -> buildJsonObject { put("merchant", r.merchant); put("contains", r.contains); put("budgetItemId", r.budgetItemId) } }))
            put("dictionary", JsonArray(entries.map { e -> buildJsonObject { put("text", e.text); put("budgetItemId", e.budgetItemId) } }))
        }
        writeObject(json)
    }

    private fun writeObject(json: kotlinx.serialization.json.JsonObject) {
        check(file.parentFile.isDirectory || file.parentFile.mkdirs() || file.parentFile.isDirectory)
        val tmp = Files.createTempFile(file.parentFile.toPath(), "receipt-categorization-", ".tmp")
        try {
            Files.writeString(tmp, json.toString())
            Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    internal fun rule(text: String, merchant: String?, dataset: Dataset): Long? = rules().firstOrNull {
        activeExpenseItem(dataset, it.budgetItemId) &&
            (it.merchant == null || key(it.merchant) == key(merchant.orEmpty())) &&
            (it.contains == null || key(text).contains(key(it.contains)))
    }?.budgetItemId

    internal fun dictionaryHint(text: String, dataset: Dataset): Long? {
        val matches = dictionary().filter { activeExpenseItem(dataset, it.budgetItemId) && key(text).contains(key(it.text)) }
            .map { it.budgetItemId }.distinct()
        val builtIn = builtInDictionary().mapNotNull { entry -> entry.meaning?.let { entry.text to it.trim().lowercase(java.util.Locale.ROOT) } }.toMap()
        return matches.singleOrNull() ?: if (matches.isEmpty()) SerbianReceiptDictionary.suggest(text, dataset, builtIn)?.id else null
    }

    private fun key(value: String) = SerbianReceiptDictionary.key(value)
}

internal fun categorizationHints(texts: List<String>, merchant: String?, dataset: Dataset, directory: File): List<ReceiptHint> {
    val store = ReceiptCategorizationStore(directory)
    val learned = ReceiptLearningStore(directory).hints(texts, merchant, dataset)
    return texts.mapIndexed { i, text ->
        val rule = store.rule(text, merchant, dataset)
        val learning = learned[i].takeIf { it.budgetItemId != null && it.confidence >= 0.7 }
        val dictionary = if (rule == null && learning == null) store.dictionaryHint(text, dataset) else null
        val id = rule ?: learning?.budgetItemId ?: dictionary
        if (rule == null && learning != null) learning else if (id == null) learned[i] else ReceiptHint(
            text, id, dataset.budgetItem(id)?.name, budgetPath(dataset, id), 0,
            if (id == null) 0.0 else 1.0, if (rule != null) "rule" else if (dictionary != null) "dictionary" else "none", null,
        )
    }
}