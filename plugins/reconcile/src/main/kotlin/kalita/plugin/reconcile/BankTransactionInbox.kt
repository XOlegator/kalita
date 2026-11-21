// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import kalita.plugin.api.BankTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime

data class BankTransactionKey(val source: String, val externalId: String)

/**
 * Posted bank transactions awaiting reconciliation, stored beside the database, not in MDB.
 * The first transaction for a key wins. Cooperating callers lock a stable sidecar file across
 * instances and processes. Invalid files cause reads and updates to fail. The directory and lock
 * file are created even on reads; the lock file must not be deleted while the inbox is in use.
 */
class BankTransactionInbox(dataDirectory: File) {
    private val file = File(dataDirectory, "bank-inbox.json")
    private val lockFile = File(dataDirectory, "bank-inbox.json.lock")

    fun read(): List<BankTransaction> = withLock { readUnlocked() }

    private fun readUnlocked(): List<BankTransaction> {
        if (!file.exists()) return emptyList()
        val transactions = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonArray.map { decode(it.jsonObject) }
        require(transactions.map { it.inboxKey() }.toSet().size == transactions.size) { "Duplicate inbox key" }
        return transactions
    }

    fun add(transactions: List<BankTransaction>) = withLock {
        val all = readUnlocked().associateByTo(LinkedHashMap()) { it.inboxKey() }
        transactions.filter { it.status == BankTransaction.Status.Posted }.forEach { all.putIfAbsent(it.inboxKey(), it) }
        if (all.isNotEmpty()) write(all.values.toList())
    }

    fun remove(keys: Set<BankTransactionKey>) = withLock {
        val all = readUnlocked()
        val remaining = all.filterNot { it.inboxKey() in keys }
        if (remaining.size != all.size) write(remaining)
    }

    private fun write(transactions: List<BankTransaction>) =
        atomicWrite(file, JsonArray(transactions.map { encode(it) }).toString())

    private fun <T> withLock(action: () -> T): T = synchronized(jvmLock) {
        Files.createDirectories(lockFile.toPath().toAbsolutePath().parent)
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    private companion object {
        val jvmLock = Any()
    }

    private fun encode(tx: BankTransaction): JsonObject = buildJsonObject {
        put("source", tx.source)
        put("externalId", tx.externalId)
        put("bankAccount", tx.bankAccount)
        put("postedAt", tx.postedAt.toString())
        put("operationAt", tx.operationAt?.toString())
        put("amount", tx.amount.toString())
        put("currency", tx.currency)
        put("originalAmount", tx.originalAmount?.toString())
        put("originalCurrency", tx.originalCurrency)
        put("description", tx.description)
        put("merchant", tx.merchant)
        put("mcc", tx.mcc)
        put("status", tx.status.name)
    }

    private fun decode(o: JsonObject) = BankTransaction(
        source = o.string("source"),
        externalId = o.string("externalId"),
        bankAccount = o.string("bankAccount"),
        postedAt = LocalDate.parse(o.string("postedAt")),
        operationAt = o.nullableString("operationAt")?.let(LocalDateTime::parse),
        amount = BigDecimal(o.string("amount")),
        currency = o.string("currency"),
        originalAmount = o.nullableString("originalAmount")?.let(::BigDecimal),
        originalCurrency = o.nullableString("originalCurrency"),
        description = o.string("description"),
        merchant = o.nullableString("merchant"),
        mcc = o.nullableString("mcc"),
        status = BankTransaction.Status.valueOf(o.string("status")).also { require(it == BankTransaction.Status.Posted) },
    )

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.let {
        require(it.isString) { "Expected string for $name" }
        it.content
    }

    private fun JsonObject.nullableString(name: String): String? =
        if (getValue(name) == JsonNull) null else string(name)
}

fun BankTransaction.inboxKey(): BankTransactionKey = BankTransactionKey(source, externalId)