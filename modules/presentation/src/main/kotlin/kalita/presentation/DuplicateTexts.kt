// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.Dataset
import kalita.core.DuplicateCandidate
import kalita.core.DuplicateFinding
import kalita.core.DuplicateOperation
import kalita.core.DuplicateReport
import kalita.core.EnrichChange
import kalita.core.EnrichField
import kalita.core.OperationDraft
import java.math.BigDecimal

/**
 * Human-readable rendering of the duplicate control's findings (dev_plan.md «Контроль дублей»),
 * shared by the Qt dialogs and the Compose screens so the wording stays the same on both shells.
 */
object DuplicateTexts {

    /** One draft the person is about to write. */
    fun draft(dataset: Dataset, label: String, draft: OperationDraft): String = buildString {
        append(label).append(": ")
        append(draft.date?.let(Formatter::date) ?: "без даты").append(" · ")
        append(Formatter.amount(draft.amount ?: BigDecimal.ZERO))
        draft.budgetItemId?.let { append(" · ").append(nameOrId(dataset.budgetItem(it)?.name, it)) }
        draft.accountFrom?.let { append(" · со счёта ").append(nameOrId(dataset.account(it)?.name, it)) }
        draft.accountTo?.let { append(" · на счёт ").append(nameOrId(dataset.account(it)?.name, it)) }
        draft.counterpartyId?.let { append(" · ").append(nameOrId(dataset.counterparty(it)?.name, it)) }
        draft.comment?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }

    /** One journal row a finding points at. */
    fun row(dataset: Dataset, d: DuplicateOperation): String = buildString {
        val op = d.operation
        append("#${op.id} · ").append(op.date?.let(Formatter::date) ?: "—").append(" · ")
        append(Formatter.amount(op.amount ?: BigDecimal.ZERO))
        op.budgetItemId?.let { append(" · ").append(nameOrId(dataset.budgetItem(it)?.name, it)) }
        append(" · ").append(nameOrId(dataset.account(op.accountFrom)?.name, op.accountFrom))
            .append(" → ").append(nameOrId(dataset.account(op.accountTo)?.name, op.accountTo))
        op.counterpartyId?.let { append(" · ").append(nameOrId(dataset.counterparty(it)?.name, it)) }
        op.comment?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        if (d.accountFromMismatch || d.accountToMismatch) append("  (счета отличаются)")
    }

    /** One question: which draft, which journal rows, how far the amounts are apart. */
    fun finding(dataset: Dataset, candidates: List<DuplicateCandidate>, finding: DuplicateFinding): String = buildString {
        val drafts = finding.candidates.mapNotNull { candidates.getOrNull(it)?.draft }
        if (finding.candidates.size > 1) {
            append("Итого по ").append(finding.candidates.size).append(" новым операциям: ")
            append(Formatter.amount(drafts.fold(BigDecimal.ZERO) { s, d -> s + (d.amount ?: BigDecimal.ZERO) }))
            drafts.firstOrNull()?.date?.let { append(" · ").append(Formatter.date(it)) }
        } else {
            val candidate = candidates.getOrNull(finding.candidates.first())
            append(draft(dataset, candidate?.label ?: Strings.OPERATION_TITLE, drafts.firstOrNull() ?: OperationDraft()))
        }
        append("\n").append(Strings.DUPLICATES_MATCHES).append(" (").append(Strings.duplicateDiff(finding.difference)).append("):")
        finding.rows.forEach { append("\n  • ").append(row(dataset, it)) }
        for (alternative in finding.alternatives) {
            append("\n  ").append(Strings.DUPLICATES_ALTERNATIVE)
            append(alternative.operations.joinToString(" + ") { row(dataset, it) })
        }
    }

    /** The whole check's text — what «Отложить» keeps in a receipt's draft and what a manual «Проверить» shows. */
    fun report(dataset: Dataset, candidates: List<DuplicateCandidate>, report: DuplicateReport): String {
        if (report.clean) return ""
        val text = report.findings.joinToString("\n\n") { finding(dataset, candidates, it) }
        return Strings.DUPLICATES_PREFIX + "\n" + text + if (report.truncated) "\n\n" + Strings.DUPLICATES_TRUNCATED else ""
    }

    /** One «дополнить старую запись» line: «Статья: Продукты», «Комментарий: Хлеб, молоко». */
    fun change(dataset: Dataset, change: EnrichChange): String =
        "${change.field.label}: ${value(dataset, change.field, change.value)}"

    private fun value(dataset: Dataset, field: EnrichField, value: Any?): String = when (field) {
        EnrichField.ACCOUNT_FROM, EnrichField.ACCOUNT_TO -> dataset.account(value as? Long)?.name ?: "id:$value"
        EnrichField.COUNTERPARTY -> dataset.counterparty(value as? Long)?.name ?: "id:$value"
        EnrichField.BUDGET_ITEM -> dataset.budgetItem(value as? Long)?.name ?: "id:$value"
        EnrichField.COMMENT -> value?.toString().orEmpty()
    }

    private fun nameOrId(name: String?, id: Long?): String = when {
        name != null -> name
        id != null -> "id:$id"
        else -> "—"
    }
}
