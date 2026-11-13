// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.core.OperationRules
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * State of the receipt window (dev_plan.md §12.19), free of any UI toolkit so the sum, the
 * problems list and the operations it would write are tested without Qt. The window edits [rows]
 * and the header fields; [drafts] is exactly what «Сохранить» writes.
 */
class ReceiptReviewModel(val proposal: ReceiptProposal, private val dataset: Dataset) {
    class Row(var text: String, var amount: BigDecimal?, var budgetItemId: Long?, val originIndex: Int?)

    var merchant: String? = proposal.merchant
    var date: LocalDate? = proposal.date
    var accountFromId: Long? = proposal.accountFromId
    var currencyId: Long? = proposal.currencyId ?: dataset.detectBaseCurrency()
    /** One operation per budget item (texts joined in the comment) instead of one per receipt line. */
    var mergeByItem: Boolean = true
    val rows: MutableList<Row> = proposal.lines.mapIndexedTo(ArrayList()) { i, l -> Row(l.text, l.amount, l.budgetItemId, i) }

    fun addRow(): Row = Row("", null, null, null).also { rows += it }

    fun linesTotal(): BigDecimal = rows.fold(BigDecimal.ZERO) { acc, r -> acc + (r.amount ?: BigDecimal.ZERO) }

    /** Receipt total minus the sum of the lines; `null` when the receipt total is unknown. Zero means the lines add up. */
    fun difference(): BigDecimal? = proposal.total?.let { it - linesTotal() }

    /** Rows whose budget item differs from the agent's choice — the window highlights them. */
    fun changedByPerson(row: Row): Boolean = row.originIndex != null && proposal.lines.getOrNull(row.originIndex)?.budgetItemId != row.budgetItemId

    private fun expenseTypeId(): Long? = dataset.operationTypes.firstOrNull { !it.deleted && it.kind == OperationKind.Expense }?.id

    private fun counterpartyId(): Long? = merchant?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { name -> dataset.counterparties.firstOrNull { !it.deleted && it.name.equals(name, ignoreCase = true) }?.id }

    /** The operations «Сохранить» would write: one per row, or one per budget item when [mergeByItem]. */
    fun drafts(): List<OperationDraft> {
        val filled = rows.filter { it.amount != null && it.budgetItemId != null }
        val shop = merchant?.trim()?.takeIf { it.isNotEmpty() }
        fun comment(texts: List<String>): String = (listOfNotNull(shop) + texts.filter { it.isNotBlank() }.joinToString("; ").takeIf { it.isNotEmpty() }.let { listOfNotNull(it) })
            .joinToString(": ").take(MAX_COMMENT)
        fun draft(amount: BigDecimal, item: Long?, text: String) = OperationDraft(
            date = date, amount = amount, currencyId = currencyId, budgetItemId = item, typeId = expenseTypeId(),
            comment = comment(listOf(text)), counterpartyId = counterpartyId(), accountFrom = accountFromId,
        )
        return if (mergeByItem) {
            filled.groupBy { it.budgetItemId }.map { (item, rs) ->
                draft(rs.fold(BigDecimal.ZERO) { a, r -> a + r.amount!! }, item, "").copy(comment = comment(rs.map { it.text }))
            }
        } else {
            filled.map { draft(it.amount!!, it.budgetItemId, it.text) }
        }
    }

    /** Human-readable reasons «Сохранить» is blocked; empty when the receipt can be written. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (rows.isEmpty()) out += "В чеке нет ни одной строки"
        if (accountFromId == null) out += "Не выбран счёт, с которого оплачено"
        if (date == null) out += "Не указана дата"
        rows.forEachIndexed { i, r ->
            val n = i + 1
            if (r.amount == null || r.amount!!.signum() <= 0) out += "Строка $n: сумма должна быть больше нуля"
            if (r.budgetItemId == null) out += "Строка $n: не выбрана статья"
        }
        if (out.isEmpty()) {
            val rules = OperationRules(dataset)
            drafts().forEach { d -> rules.validate(d).forEach { out += "Операция «${d.comment ?: ""}»: ${it.name}" } }
        }
        return out
    }

    fun resolution(changeSetId: String?, savedOperations: Int, now: Instant = Instant.now()): ReceiptResolution = ReceiptResolution(
        resolvedAt = now,
        merchant = merchant,
        date = date,
        accountFromId = accountFromId,
        currencyId = currencyId,
        lines = rows.filter { it.amount != null }.map { ReceiptFinalLine(it.text, it.amount!!, it.budgetItemId, it.originIndex) },
        savedOperations = savedOperations,
        changeSetId = changeSetId,
        reason = null,
    )

    private companion object {
        const val MAX_COMMENT = 250
    }
}
