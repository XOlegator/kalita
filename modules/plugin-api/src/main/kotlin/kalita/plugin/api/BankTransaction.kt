// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One bank-side transaction, as produced by a [StatementImporter] or [BankConnector]
 * (dev_plan.md §11.2) — the reconciliation algorithm (§11.3) that turns these into journal
 * matches or draft operations lives in `core`/`agent-api`, not here; this module only carries
 * the plugin's output shape.
 */
data class BankTransaction(
    /** Which plugin/bank produced this — becomes part of [externalId]'s dedup scope. */
    val source: String,
    /** The bank API's own id, or a hash of (account, date, amount, description, statement line number). */
    val externalId: String,
    /** The bank's own card/account identifier — mapped to a Kalita [Table.Accounts] row once (FR-B3), not here. */
    val bankAccount: String,
    val postedAt: LocalDate,
    val operationAt: LocalDateTime?,
    /** Signed, in [currency]. */
    val amount: BigDecimal,
    val currency: String,
    /** Set when the purchase was made in a different currency than the account's. */
    val originalAmount: BigDecimal? = null,
    val originalCurrency: String? = null,
    val description: String,
    val merchant: String? = null,
    val mcc: String? = null,
    val status: Status,
) {
    /** Only [Posted] transactions are imported (§11.2); a hold is re-seen once it posts. */
    enum class Status { Posted, Pending }
}

/**
 * A file-based bank statement → [BankTransaction] (dev_plan.md §11.5: CSV with configurable
 * column mapping, OFX/QFX — the universal path, built first; bank-specific CSV templates live
 * in the bank's own plugin on top of this contract).
 */
interface StatementImporter {
    /** File extensions this importer claims, lower-case without the dot (e.g. `"csv"`, `"ofx"`). */
    val supportedExtensions: Set<String>

    suspend fun import(fileBytes: ByteArray, fileName: String): List<BankTransaction>
}

/**
 * A bank's own API → [BankTransaction] for a period (dev_plan.md §11.5). Authorization
 * (OAuth/token) goes through [PluginHost.secretStore] — a connector must not manage its own
 * token storage.
 */
interface BankConnector {
    suspend fun fetch(host: PluginHost, bankAccount: String, from: LocalDate, to: LocalDate): List<BankTransaction>
}
