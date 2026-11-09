// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import java.math.BigDecimal
import java.time.LocalDateTime

/** A receipt, as parsed by a [ReceiptRecognizer] (dev_plan.md §12.4). */
data class ReceiptDraft(
    val store: String? = null,
    val inn: String? = null,
    val dateTime: LocalDateTime? = null,
    val total: BigDecimal? = null,
    val items: List<Item> = emptyList(),
    /** Fiscal data's `fn`/`fd`/`fp` triple (or the RS equivalent) — the key used to dedupe re-recognized receipts. */
    val fiscal: Fiscal? = null,
    val payment: Payment? = null,
    /** How sure the recognizer is about each field (§12.3 "Самопроверка" lowers these on a failed cross-check). */
    val confidence: Map<Field, Float> = emptyMap(),
    val pages: List<ImageRef> = emptyList(),
) {
    data class Item(
        val name: String,
        val qty: BigDecimal,
        val price: BigDecimal,
        val sum: BigDecimal,
        val vatRate: BigDecimal? = null,
        /** Bounding box on the page it was read from, for highlighting in the confirmation UI (§12.4). */
        val bbox: BoundingBox? = null,
    )

    data class BoundingBox(val page: Int, val x: Int, val y: Int, val width: Int, val height: Int)

    data class Fiscal(val fn: String, val fd: String, val fp: String)

    enum class Payment { Cash, Card }

    enum class Field { Store, Inn, DateTime, Total, Items, Fiscal, Payment }
}

/** An opaque reference to one decoded page image; the host owns the actual bytes/storage (§12.4 "изображение сохраняется"). */
@JvmInline
value class ImageRef(val id: String)

/**
 * Where receipt files come from (dev_plan.md §12.2): a file-picker dialog, drag-and-drop, a
 * watched "scanner inbox" folder, the camera, or a share-target — the desktop and Android
 * shells implement this for their own built-in sources; `receipts` adds more (e.g. "Share to
 * Kalita" from mail/messenger apps).
 */
interface DocumentSource {
    suspend fun poll(): List<ByteArray>
}

/** A decoded receipt file (PDF/image) → [ReceiptDraft] (dev_plan.md §12.1, §12.3). */
interface ReceiptRecognizer {
    suspend fun recognize(host: PluginHost, fileBytes: ByteArray, fileName: String): ReceiptDraft
}

/** One candidate the [Categorizer] proposes for an ambiguous field, with its confidence in `[0, 1]`. */
data class CategorizerSuggestion(val budgetItemId: Long?, val accountId: Long?, val counterpartyId: Long?, val confidence: Float)

/**
 * Proposes a budget item / account / counterparty for a draft operation coming from a receipt
 * or a bank transaction (dev_plan.md §11.3 step 5, §12.4): user rules first, then history, then
 * (optionally) a model — in that priority order, decided by the implementation, not by the host.
 */
interface Categorizer {
    suspend fun suggest(description: String, merchant: String?, mcc: String?): List<CategorizerSuggestion>
}
