// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.agent.api.ReceiptReviewModel
import kalita.core.Dataset
import kalita.core.DuplicateSearch
import java.math.BigDecimal

internal fun receiptDuplicateWarning(model: ReceiptReviewModel, dataset: Dataset): String? {
    val drafts = model.drafts()
    val targets = drafts.mapIndexed { index, draft -> "Операция ${index + 1}" to draft }.toMutableList()
    if (drafts.size > 1) {
        targets += "Общий итог" to drafts.first().copy(amount = drafts.fold(BigDecimal.ZERO) { sum, draft -> sum + (draft.amount ?: BigDecimal.ZERO) })
    }
    val warnings = targets.mapNotNull { (label, draft) ->
        val result = DuplicateSearch.find(draft, dataset.operations)
        if (result.matches.isEmpty() && !result.groupsTruncated) return@mapNotNull null
        buildString {
            append("$label (${draft.amount}):")
            result.matches.forEach { match ->
                append("\n• ")
                append(match.operations.joinToString(" + ") { candidate ->
                    val op = candidate.operation
                    "id ${op.id}, ${op.date}, ${op.amount}, счёт ${op.accountFrom ?: "—"} → ${op.accountTo ?: "—"}, ${op.comment.orEmpty()}" +
                        if (candidate.accountFromMismatch || candidate.accountToMismatch) " (счета отличаются)" else ""
                })
            }
            if (result.groupsTruncated) append("\nПоиск групп неполный (более 20 кандидатов).")
        }
    }
    return warnings.takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "Возможные дубли в журнале (не доказательство):\n")
}