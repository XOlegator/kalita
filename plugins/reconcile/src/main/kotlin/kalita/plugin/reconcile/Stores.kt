// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import kalita.plugin.api.BankTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** The dedup key of one bank-side transaction across imports (dev_plan.md §11.2: `source + externalId`). */
val BankTransaction.key: String get() = "$source|$externalId"

/**
 * FR-B3 (dev_plan.md §11.2): which Kalita account a bank's account/client is, and which Kalita
 * currency a statement's ISO code is — asked once, remembered per database in
 * `<dataDirectory>/bank-mappings.json`. Keys are whatever the importer put in
 * [BankTransaction.bankAccount] / [BankTransaction.currency]; one Kalita account may be the
 * target of several bank keys (a client with an RSD and an EUR account in the same bank).
 */
class BankMappingStore(dataDirectory: File) {
    private val file = File(dataDirectory, "bank-mappings.json")

    data class Mappings(val accounts: Map<String, Long>, val currencies: Map<String, Long>)

    fun read(): Mappings {
        if (!file.isFile) return Mappings(emptyMap(), emptyMap())
        val text = file.readText()
        if (text.isBlank()) return Mappings(emptyMap(), emptyMap())
        val root = Json.parseToJsonElement(text).jsonObject
        return Mappings(
            root["accounts"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long }.orEmpty(),
            root["currencies"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long }.orEmpty(),
        )
    }

    fun accountFor(bankAccount: String): Long? = read().accounts[bankAccount]

    fun currencyFor(code: String): Long? = read().currencies[code.uppercase()]

    fun setAccount(bankAccount: String, accountId: Long?) = write(read().let { m -> m.copy(accounts = m.accounts.updated(bankAccount, accountId)) })

    fun setCurrency(code: String, currencyId: Long?) = write(read().let { m -> m.copy(currencies = m.currencies.updated(code.uppercase(), currencyId)) })

    private fun Map<String, Long>.updated(key: String, value: Long?): Map<String, Long> =
        if (value == null) this - key else this + (key to value)

    private fun write(m: Mappings) {
        val json = buildJsonObject {
            put("accounts", JsonObject(m.accounts.mapValues { JsonPrimitive(it.value) }))
            put("currencies", JsonObject(m.currencies.mapValues { JsonPrimitive(it.value) }))
        }
        atomicWrite(file, json.toString())
    }
}

/** How a bank transaction is tied to the journal (dev_plan.md §11.2 `ReconLink.kind`). */
enum class LinkKind {
    /** One journal row with the same amount. */
    EXACT,

    /** Several journal rows adding up to the bank amount (an itemized receipt). */
    SUM,

    /** The user picked or moved the row(s) by hand. */
    MANUAL,

    /** A new operation was created from the bank line. */
    CREATED,

    /** The user said this bank line needs no journal row (already accounted for otherwise). */
    IGNORED,
}

data class ReconLink(
    val bankTx: String,
    val rows: List<Long>,
    val kind: LinkKind,
    val at: Instant,
    val device: String,
)

/**
 * The links made so far (dev_plan.md §11.2, FR-B6 "повторный импорт ничего не дублирует"):
 * `<dataDirectory>/bank-links.jsonl`, append-only — a decision is never edited in place, a later
 * line about the same bank transaction supersedes an earlier one.
 */
class ReconLinkStore(dataDirectory: File) {
    private val file = File(dataDirectory, "bank-links.jsonl")

    fun readAll(): List<ReconLink> {
        if (!file.isFile) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { decode(Json.parseToJsonElement(it).jsonObject) }
    }

    /** The current (last-written) link per bank transaction. */
    fun current(): Map<String, ReconLink> = readAll().associateBy { it.bankTx }

    fun linkedBankTransactions(): Set<String> = current().keys

    fun linkedRows(): Set<Long> = current().values.flatMapTo(HashSet()) { it.rows }

    fun append(links: List<ReconLink>) {
        if (links.isEmpty()) return
        file.parentFile.mkdirs()
        file.appendText(links.joinToString("") { encode(it).toString() + "\n" })
    }

    private fun encode(l: ReconLink): JsonObject = buildJsonObject {
        put("bankTx", l.bankTx)
        put("rows", JsonArray(l.rows.map { JsonPrimitive(it) }))
        put("kind", l.kind.name)
        put("at", l.at.toString())
        put("device", l.device)
    }

    private fun decode(o: JsonObject) = ReconLink(
        bankTx = o.getValue("bankTx").jsonPrimitive.content,
        rows = o["rows"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty(),
        kind = LinkKind.valueOf(o.getValue("kind").jsonPrimitive.content),
        at = Instant.parse(o.getValue("at").jsonPrimitive.content),
        device = o["device"]?.jsonPrimitive?.content ?: "",
    )
}

private fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
