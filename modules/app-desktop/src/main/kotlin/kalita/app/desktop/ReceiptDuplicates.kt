// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.agent.api.ReceiptReviewModel
import kalita.core.Dataset
import kalita.core.DuplicateSearch

internal fun receiptDuplicateWarning(model: ReceiptReviewModel, dataset: Dataset): String {
    val drafts = model.drafts()
    val checks = drafts.mapIndexed { i, draft -> "Операция ${i + 1}" to draft }.toMutableList()
    if (drafts.size > 1) {
        checks += "Итого по чеку" to drafts.first().copy(amount = drafts.sumOf { it.amount!! })
    }
    return checks.mapNotNull { (label, draft) ->
        val result = DuplicateSearch.find(draft, dataset.operations)
        if (result.matches.isEmpty() && !result.groupsTruncated) return@mapNotNull null
        buildString {
            append("$label (${draft.amount}):")
            result.matches.forEach { match ->
                append("\n  ")
                append(match.operations.joinToString(" + ") { duplicate ->
                    val op = duplicate.operation
                    "#${op.id}, ${op.date}, ${op.amount}, счёт ${op.accountFrom ?: "—"} → ${op.accountTo ?: "—"}, ${op.comment.orEmpty()}" +
                        if (duplicate.accountFromMismatch || duplicate.accountToMismatch) " (счета отличаются)" else ""
                })
            }
            if (result.groupsTruncated) append("\n  Поиск групп неполный (ограничение 20 операций).")
        }
    }.joinToString("\n\n")
}