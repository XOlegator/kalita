// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

const val RECEIPT_STATUS_PENDING = "pending"
const val RECEIPT_STATUS_SAVED = "saved"
const val RECEIPT_STATUS_REJECTED = "rejected"

/** One position of a receipt as the agent read it, with the budget item the agent picked (if any). */
data class ReceiptLine(val text: String, val amount: BigDecimal, val budgetItemId: Long?)

/** A line as the person left it. [originIndex] is the index of the agent's line it came from, `null` for a line the person added. */
data class ReceiptFinalLine(val text: String, val amount: BigDecimal, val budgetItemId: Long?, val originIndex: Int?)

/** The person's decision on a [ReceiptProposal]: either the lines that were saved, or a rejection with an optional [reason]. */
data class ReceiptResolution(
    val resolvedAt: Instant,
    val merchant: String?,
    val date: LocalDate?,
    val accountFromId: Long?,
    val currencyId: Long?,
    val lines: List<ReceiptFinalLine>,
    val savedOperations: Int,
    val changeSetId: String?,
    val reason: String?,
)

/**
 * A receipt an agent asks the person to review before anything is written (the «окно чека»,
 * dev_plan.md §12.19). It lives outside `Data.mdb`, in `<dataDirectory>/receipt-proposals/<id>.json`:
 * the running program shows it, the person corrects the budget items and decides, and the agent
 * reads the outcome back through `get_receipt_proposal`.
 */
data class ReceiptProposal(
    val id: String,
    val createdAt: Instant,
    val device: String,
    val clientRequestId: String,
    val merchant: String?,
    val date: LocalDate?,
    val currencyId: Long?,
    val accountFromId: Long?,
    /** The grand total printed on the receipt — the window compares it with the sum of the lines. */
    val total: BigDecimal?,
    val note: String?,
    /** Path to the receipt photo/scan on this machine, if the agent knows it — the window can show it. */
    val imagePath: String?,
    val lines: List<ReceiptLine>,
    val status: String,
    val resolution: ReceiptResolution?,
)

/** `<dataDirectory>/receipt-proposals/`, one JSON file per proposal, rewritten whole through a temp file (atomic move). */
class ReceiptProposalStore(dataDirectory: File) {
    private val dir = File(dataDirectory, "receipt-proposals")
    private val learning = ReceiptLearningStore(dataDirectory)

    fun all(): List<ReceiptProposal> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { runCatching { decode(Json.parseToJsonElement(it.readText()).jsonObject) }.getOrNull() }
            .sortedBy { it.createdAt }
    }

    fun pending(): List<ReceiptProposal> = all().filter { it.status == RECEIPT_STATUS_PENDING }

    fun find(id: String): ReceiptProposal? = File(dir, "${safe(id)}.json").takeIf { it.isFile }
        ?.let { runCatching { decode(Json.parseToJsonElement(it.readText()).jsonObject) }.getOrNull() }

    fun findByClientRequestId(clientRequestId: String): ReceiptProposal? = all().firstOrNull { it.clientRequestId == clientRequestId }

    fun add(proposal: ReceiptProposal): ReceiptProposal {
        write(proposal)
        return proposal
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Marks [proposal] saved with [resolution] and appends what was learned from it to [ReceiptLearningStore]. */
    fun complete(proposal: ReceiptProposal, resolution: ReceiptResolution, dataset: Dataset): ReceiptProposal {
        val saved = proposal.copy(status = RECEIPT_STATUS_SAVED, resolution = resolution)
        write(saved)
        learning.append(ReceiptFeedback.record(saved, dataset))
        return saved
    }

    fun reject(proposal: ReceiptProposal, reason: String?, dataset: Dataset, now: Instant = Instant.now()): ReceiptProposal {
        val resolution = ReceiptResolution(now, proposal.merchant, proposal.date, proposal.accountFromId, proposal.currencyId, emptyList(), 0, null, reason)
        val rejected = proposal.copy(status = RECEIPT_STATUS_REJECTED, resolution = resolution)
        write(rejected)
        learning.append(ReceiptFeedback.record(rejected, dataset))
        return rejected
    }

    private fun write(p: ReceiptProposal) {
        dir.mkdirs()
        val target = File(dir, "${safe(p.id)}.json")
        val tmp = File(dir, "${safe(p.id)}.json.tmp")
        tmp.writeText(encode(p).toString())
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }

    private fun encode(p: ReceiptProposal): JsonObject = buildJsonObject {
        put("id", p.id)
        put("createdAt", p.createdAt.toString())
        put("device", p.device)
        put("clientRequestId", p.clientRequestId)
        put("merchant", p.merchant)
        put("date", p.date?.toString())
        put("currencyId", p.currencyId)
        put("accountFromId", p.accountFromId)
        put("total", p.total?.toPlainString())
        put("note", p.note)
        put("imagePath", p.imagePath)
        put("lines", JsonArray(p.lines.map { l -> buildJsonObject { put("text", l.text); put("amount", l.amount.toPlainString()); put("budgetItemId", l.budgetItemId) } }))
        put("status", p.status)
        p.resolution?.let { r ->
            put(
                "resolution",
                buildJsonObject {
                    put("resolvedAt", r.resolvedAt.toString())
                    put("merchant", r.merchant)
                    put("date", r.date?.toString())
                    put("accountFromId", r.accountFromId)
                    put("currencyId", r.currencyId)
                    put(
                        "lines",
                        JsonArray(
                            r.lines.map { l ->
                                buildJsonObject {
                                    put("text", l.text)
                                    put("amount", l.amount.toPlainString())
                                    put("budgetItemId", l.budgetItemId)
                                    put("originIndex", l.originIndex)
                                }
                            },
                        ),
                    )
                    put("savedOperations", r.savedOperations)
                    put("changeSetId", r.changeSetId)
                    put("reason", r.reason)
                },
            )
        }
    }

    private fun decode(o: JsonObject): ReceiptProposal = ReceiptProposal(
        id = o.getValue("id").jsonPrimitive.content,
        createdAt = Instant.parse(o.getValue("createdAt").jsonPrimitive.content),
        device = o.getValue("device").jsonPrimitive.content,
        clientRequestId = o.getValue("clientRequestId").jsonPrimitive.content,
        merchant = o["merchant"]?.jsonPrimitive?.contentOrNull,
        date = o["date"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
        currencyId = o["currencyId"]?.jsonPrimitive?.longOrNull,
        accountFromId = o["accountFromId"]?.jsonPrimitive?.longOrNull,
        total = o["total"]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal),
        note = o["note"]?.jsonPrimitive?.contentOrNull,
        imagePath = o["imagePath"]?.jsonPrimitive?.contentOrNull,
        lines = o.getValue("lines").jsonArray.map {
            val l = it.jsonObject
            ReceiptLine(l.getValue("text").jsonPrimitive.content, BigDecimal(l.getValue("amount").jsonPrimitive.content), l["budgetItemId"]?.jsonPrimitive?.longOrNull)
        },
        status = o.getValue("status").jsonPrimitive.content,
        resolution = o["resolution"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject?.let { r ->
            ReceiptResolution(
                resolvedAt = Instant.parse(r.getValue("resolvedAt").jsonPrimitive.content),
                merchant = r["merchant"]?.jsonPrimitive?.contentOrNull,
                date = r["date"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
                accountFromId = r["accountFromId"]?.jsonPrimitive?.longOrNull,
                currencyId = r["currencyId"]?.jsonPrimitive?.longOrNull,
                lines = r.getValue("lines").jsonArray.map {
                    val l = it.jsonObject
                    ReceiptFinalLine(
                        l.getValue("text").jsonPrimitive.content,
                        BigDecimal(l.getValue("amount").jsonPrimitive.content),
                        l["budgetItemId"]?.jsonPrimitive?.longOrNull,
                        l["originIndex"]?.jsonPrimitive?.intOrNull,
                    )
                },
                savedOperations = r.getValue("savedOperations").jsonPrimitive.content.toInt(),
                changeSetId = r["changeSetId"]?.jsonPrimitive?.contentOrNull,
                reason = r["reason"]?.jsonPrimitive?.contentOrNull,
            )
        },
    )
}

/** What happened to one agent line (or one line the person added) once the person was done. */
enum class LineOutcomeKind { Confirmed, Corrected, Added, Removed }

data class LineOutcome(
    val kind: LineOutcomeKind,
    val text: String,
    val amount: BigDecimal,
    val agentBudgetItemId: Long?,
    val finalBudgetItemId: Long?,
)

/** Compares what the agent proposed with what the person saved (dev_plan.md §12.19, «обучающий слой»). */
object ReceiptFeedback {
    fun outcomes(p: ReceiptProposal): List<LineOutcome> {
        val r = p.resolution ?: return emptyList()
        if (p.status == RECEIPT_STATUS_REJECTED) return emptyList()
        val result = ArrayList<LineOutcome>()
        val seen = HashSet<Int>()
        for (line in r.lines) {
            val origin = line.originIndex?.let { p.lines.getOrNull(it) }
            if (origin == null) {
                result += LineOutcome(LineOutcomeKind.Added, line.text, line.amount, null, line.budgetItemId)
            } else {
                seen += line.originIndex
                val kind = if (origin.budgetItemId == line.budgetItemId) LineOutcomeKind.Confirmed else LineOutcomeKind.Corrected
                result += LineOutcome(kind, origin.text, line.amount, origin.budgetItemId, line.budgetItemId)
            }
        }
        p.lines.forEachIndexed { i, l -> if (i !in seen) result += LineOutcome(LineOutcomeKind.Removed, l.text, l.amount, l.budgetItemId, null) }
        return result
    }

    fun record(p: ReceiptProposal, dataset: Dataset): JsonObject = buildJsonObject {
        val r = p.resolution
        put("at", (r?.resolvedAt ?: Instant.now()).toString())
        put("proposalId", p.id)
        put("device", p.device)
        put("merchant", r?.merchant ?: p.merchant)
        put("status", p.status)
        put("reason", r?.reason)
        put(
            "lines",
            JsonArray(
                outcomes(p).map { o ->
                    buildJsonObject {
                        put("kind", o.kind.name.lowercase())
                        put("text", o.text)
                        put("amount", o.amount.toPlainString())
                        put("agentItemId", o.agentBudgetItemId)
                        put("agentItem", dataset.budgetItem(o.agentBudgetItemId)?.name)
                        put("finalItemId", o.finalBudgetItemId)
                        put("finalItem", dataset.budgetItem(o.finalBudgetItemId)?.name)
                    }
                },
            ),
        )
    }
}

/** What the learning layer suggests for one receipt line text. */
data class ReceiptHint(
    val text: String,
    val budgetItemId: Long?,
    val budgetItemName: String?,
    val path: String?,
    /** How many earlier, person-approved lines back this suggestion. */
    val evidence: Int,
    /** Share of the evidence (corrections weigh double) that points at [budgetItemId], 0…1. */
    val confidence: Double,
    /** `exact` (same normalized text), `similar` (shared words) or `none`. */
    val basis: String,
    /** The item the agent proposed earlier and the person changed away from, if that happened for this text. */
    val oftenCorrectedFrom: String?,
)

/**
 * The learning layer: `<dataDirectory>/receipt-learning.jsonl`, append-only, **outside `Data.mdb`**
 * (dev_plan.md §12.19). One line per resolved receipt with, for every line, what the agent proposed
 * and what the person finally chose. Only the person's final choices count as evidence.
 */
class ReceiptLearningStore(dataDirectory: File) {
    private val file = File(dataDirectory, "receipt-learning.jsonl")

    fun append(record: JsonObject) {
        file.parentFile.mkdirs()
        file.appendText(record.toString() + "\n")
    }

    private class Sample(val text: String, val merchant: String, val itemId: Long, val corrected: Boolean, val correctedFrom: String?)

    private fun samples(): List<Sample> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<Sample>()
        for (line in file.readLines()) {
            if (line.isBlank()) continue
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["status"]?.jsonPrimitive?.contentOrNull != RECEIPT_STATUS_SAVED) continue
            val merchant = normalize(o["merchant"]?.jsonPrimitive?.contentOrNull.orEmpty())
            for (l in o["lines"]?.jsonArray.orEmpty()) {
                val lo = l.jsonObject
                val kind = lo["kind"]?.jsonPrimitive?.contentOrNull
                if (kind == "removed") continue
                val itemId = lo["finalItemId"]?.jsonPrimitive?.longOrNull ?: continue
                out += Sample(normalize(lo["text"]?.jsonPrimitive?.contentOrNull.orEmpty()), merchant, itemId, kind == "corrected", lo["agentItem"]?.jsonPrimitive?.contentOrNull)
            }
        }
        return out
    }

    fun hints(texts: List<String>, merchant: String?, dataset: Dataset): List<ReceiptHint> {
        val all = samples().filter { dataset.budgetItem(it.itemId)?.deleted == false }
        val shop = normalize(merchant.orEmpty())
        return texts.map { text ->
            val key = normalize(text)
            val exact = if (key.isEmpty()) emptyList() else all.filter { it.text == key }
            val (pool, basis) = when {
                exact.isNotEmpty() -> exact to "exact"
                key.isEmpty() -> emptyList<Sample>() to "none"
                else -> all.filter { similar(key, it.text) }.let { it to if (it.isEmpty()) "none" else "similar" }
            }
            if (pool.isEmpty()) return@map ReceiptHint(text, null, null, null, 0, 0.0, "none", null)
            // A correction is a stronger signal than a confirmation; the same shop counts a bit more.
            fun weight(s: Sample) = (if (s.corrected) 2.0 else 1.0) * (if (shop.isNotEmpty() && s.merchant == shop) 1.5 else 1.0)
            val votes = pool.groupBy { it.itemId }.mapValues { (_, v) -> v.sumOf(::weight) }
            val best = votes.maxBy { it.value }
            val chosen = pool.filter { it.itemId == best.key }
            ReceiptHint(
                text = text,
                budgetItemId = best.key,
                budgetItemName = dataset.budgetItem(best.key)?.name,
                path = budgetPath(dataset, best.key),
                evidence = chosen.size,
                confidence = best.value / votes.values.sum(),
                basis = basis,
                oftenCorrectedFrom = chosen.filter { it.corrected }.mapNotNull { it.correctedFrom }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            )
        }
    }

    companion object {
        /** Lower-cases, drops punctuation and every token containing a digit (weights, prices, sizes). */
        fun normalize(s: String): String = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it.none(Char::isDigit) }
            .joinToString(" ")

        private fun similar(a: String, b: String): Boolean {
            val ta = a.split(' ').toSet()
            val tb = b.split(' ').toSet()
            if (ta.isEmpty() || tb.isEmpty()) return false
            val common = ta.intersect(tb).size
            return common.toDouble() / (ta.size + tb.size - common) >= 0.5
        }
    }
}

/** «Расход / Продукты / Еда» — the chain of names from the root down to the item. */
fun budgetPath(dataset: Dataset, id: Long?): String? {
    var item = dataset.budgetItem(id) ?: return null
    val names = ArrayList<String>()
    val seen = HashSet<Long>()
    while (seen.add(item.id)) {
        names += item.name ?: "?"
        item = dataset.budgetItem(item.parentId) ?: break
    }
    return names.asReversed().joinToString(" / ")
}
