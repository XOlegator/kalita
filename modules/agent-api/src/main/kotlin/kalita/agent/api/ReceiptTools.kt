// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** The agent's input to `propose_receipt`; ids are already resolved (`resolve`), amounts are the receipt's own. */
data class ReceiptProposalInput(
    val merchant: String?,
    val date: LocalDate?,
    val currencyId: Long?,
    val accountFromId: Long?,
    val total: BigDecimal?,
    val note: String?,
    val imagePath: String?,
    val lines: List<ReceiptLine>,
) {
    companion object {
        /** Shared by the CLI and MCP: `{merchant, date, currencyId, accountFromId, total, note, imagePath, lines:[{text, amount, budgetItemId}]}`. */
        fun fromJson(o: JsonObject) = ReceiptProposalInput(
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
        )
    }
}

/**
 * `propose_receipt` (dev_plan.md §12.19): hands a recognized receipt to the person for review. Nothing
 * is written to the database — the program shows the «окно чека», the person fixes the budget items
 * and saves or rejects. Allowed in `draft` and `write` mode (the review *is* the confirmation), not
 * in `read-only`. Idempotent per [clientRequestId].
 */
class ProposeReceiptTool(private val session: AgentSession) {
    fun execute(input: ReceiptProposalInput, mode: WriteMode, clientRequestId: String, device: String): JsonObject {
        val store = ReceiptProposalStore(session.dataDirectory)
        store.findByClientRequestId(clientRequestId)?.let { return summary(it, replayed = true) }
        if (mode == WriteMode.ReadOnly) return errorResult("read-only mode: propose_receipt should not have been called")

        val dataset = session.dataset
        val problems = ArrayList<String>()
        if (input.lines.isEmpty()) problems += "lines is empty"
        input.lines.forEachIndexed { i, l ->
            if (l.amount.signum() <= 0) problems += "lines[$i]: amount must be positive"
            if (l.text.isBlank()) problems += "lines[$i]: text is empty"
            if (l.budgetItemId != null && dataset.budgetItem(l.budgetItemId)?.deleted != false) problems += "lines[$i]: unknown budgetItemId ${l.budgetItemId}"
        }
        if (input.accountFromId != null && dataset.account(input.accountFromId)?.deleted != false) problems += "unknown accountFromId ${input.accountFromId}"
        if (input.currencyId != null && dataset.currency(input.currencyId)?.deleted != false) problems += "unknown currencyId ${input.currencyId}"
        if (problems.isNotEmpty()) {
            return buildJsonObject {
                put("ok", false)
                put("error", "validation_failed")
                put("problems", JsonArray(problems.map(::JsonPrimitive)))
            }
        }
        val proposal = store.add(
            ReceiptProposal(
                id = store.newId(), createdAt = Instant.now(), device = device, clientRequestId = clientRequestId,
                merchant = input.merchant?.trim()?.ifEmpty { null }, date = input.date, currencyId = input.currencyId,
                accountFromId = input.accountFromId, total = input.total, note = input.note, imagePath = input.imagePath,
                lines = input.lines, status = RECEIPT_STATUS_PENDING, resolution = null,
            ),
        )
        return summary(proposal, replayed = false)
    }

    private fun summary(p: ReceiptProposal, replayed: Boolean): JsonObject {
        val linesTotal = p.lines.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
        return buildJsonObject {
            put("ok", true)
            put("proposalId", p.id)
            put("status", p.status)
            put("replayed", replayed)
            put("linesTotal", linesTotal.toPlainString())
            put("receiptTotal", p.total?.toPlainString())
            put("linesMatchReceiptTotal", p.total?.let { it.compareTo(linesTotal) == 0 })
            put(
                "message",
                "Окно проверки чека показано пользователю в программе Kalita (если программа закрыта — появится при запуске). " +
                    "Ничего не записано в базу: пользователь проверит статьи и сохранит или отклонит. " +
                    "Результат — get_receipt_proposal.",
            )
        }
    }
}

/**
 * `get_receipt_proposal`: the person's decision on a proposal. For a saved receipt, `lines` says per
 * line whether the person confirmed the agent's budget item, corrected it, added the line or removed
 * it — read this and adjust next time (and call `get_receipt_hints` before the next receipt).
 */
class GetReceiptProposalTool(private val session: AgentSession) {
    fun execute(proposalId: String): JsonObject {
        val p = ReceiptProposalStore(session.dataDirectory).find(proposalId) ?: return errorResult("no such receipt proposal: $proposalId")
        val dataset = session.dataset
        return buildJsonObject {
            put("ok", true)
            put("proposalId", p.id)
            put("status", p.status)
            p.resolution?.let { r ->
                put("resolvedAt", r.resolvedAt.toString())
                put("savedOperations", r.savedOperations)
                put("changeSetId", r.changeSetId)
                put("reason", r.reason)
                put("merchant", r.merchant)
                put("date", r.date?.toString())
                put("accountFrom", dataset.account(r.accountFromId)?.name)
                put(
                    "lines",
                    JsonArray(
                        ReceiptFeedback.outcomes(p).map { o ->
                            buildJsonObject {
                                put("outcome", o.kind.name.lowercase())
                                put("text", o.text)
                                put("amount", o.amount.toPlainString())
                                put("agentBudgetItem", budgetPath(dataset, o.agentBudgetItemId))
                                put("finalBudgetItem", budgetPath(dataset, o.finalBudgetItemId))
                            }
                        },
                    ),
                )
            }
        }
    }
}

/**
 * `get_receipt_hints`: what earlier receipts taught — for each line text, the budget item the person
 * finally chose for the same or a similar line (corrections weigh more, the same shop a bit more).
 * Call it **before** `propose_receipt` and prefer these over a fresh guess when `confidence` is high.
 */
class GetReceiptHintsTool(private val session: AgentSession) {
    fun execute(texts: List<String>, merchant: String?): JsonObject {
        val hints = ReceiptLearningStore(session.dataDirectory).hints(texts, merchant, session.dataset)
        return buildJsonObject {
            put("ok", true)
            put(
                "hints",
                JsonArray(
                    hints.map { h ->
                        buildJsonObject {
                            put("text", h.text)
                            put("budgetItemId", h.budgetItemId)
                            put("budgetItem", h.path)
                            put("evidence", h.evidence)
                            put("confidence", Math.round(h.confidence * 100) / 100.0)
                            put("basis", h.basis)
                            put("oftenCorrectedFrom", h.oftenCorrectedFrom)
                        }
                    },
                ),
            )
        }
    }
}

/** True if [path] exists as a readable file — the window only offers «Показать снимок» then. */
fun receiptImageAvailable(path: String?): Boolean = path != null && File(path).isFile
