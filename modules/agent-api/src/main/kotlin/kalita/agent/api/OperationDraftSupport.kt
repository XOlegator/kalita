// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kalita.core.OperationDraft
import kalita.storage.api.toOperationColumns
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

/** The one shared draft→row mapping lives in storage-api ([toOperationColumns]); this name stays for the callers here. */
internal fun draftToColumns(draft: OperationDraft): Map<String, Any?> = draft.toOperationColumns()

/** Lossless JSON of an [OperationDraft] — the inbox file's row shape and `op add --batch`'s input shape. */
internal object OperationDraftJson {
    fun encode(draft: OperationDraft): JsonObject = buildJsonObject {
        put("date", draft.date?.toString())
        put("amount", draft.amount?.toPlainString())
        put("currencyId", draft.currencyId)
        put("budgetItemId", draft.budgetItemId)
        put("typeId", draft.typeId)
        put("comment", draft.comment)
        put("counterpartyId", draft.counterpartyId)
        put("accountFrom", draft.accountFrom)
        put("accountTo", draft.accountTo)
    }

    fun decode(o: JsonObject): OperationDraft = OperationDraft(
        date = o["date"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
        amount = o["amount"]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal),
        currencyId = o["currencyId"]?.jsonPrimitive?.longOrNull,
        budgetItemId = o["budgetItemId"]?.jsonPrimitive?.longOrNull,
        typeId = o["typeId"]?.jsonPrimitive?.longOrNull,
        comment = o["comment"]?.jsonPrimitive?.contentOrNull,
        counterpartyId = o["counterpartyId"]?.jsonPrimitive?.longOrNull,
        accountFrom = o["accountFrom"]?.jsonPrimitive?.longOrNull,
        accountTo = o["accountTo"]?.jsonPrimitive?.longOrNull,
    )
}

/**
 * The «предпросмотр» of one draft (dev_plan.md §12.14): what would be written, with ids resolved
 * to the names the agent and the human would recognise.
 */
internal fun previewOf(dataset: Dataset, draft: OperationDraft): JsonObject = buildJsonObject {
    put("date", draft.date?.toString())
    put("amount", draft.amount?.toPlainString())
    put("currency", dataset.currency(draft.currencyId)?.name)
    put("budgetItem", dataset.budgetItem(draft.budgetItemId)?.name)
    put("operationType", dataset.operationType(draft.typeId)?.name)
    put("counterparty", dataset.counterparty(draft.counterpartyId)?.name)
    put("accountFrom", dataset.account(draft.accountFrom)?.name)
    put("accountTo", dataset.account(draft.accountTo)?.name)
    put("comment", draft.comment)
}
