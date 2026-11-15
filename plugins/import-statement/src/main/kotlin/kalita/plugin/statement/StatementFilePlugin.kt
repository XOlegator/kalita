// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.statement

import kalita.plugin.api.BankTransaction
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginDescriptor
import kalita.plugin.api.PluginVersion
import kalita.plugin.api.StatementImporter

/**
 * Universal `StatementImporter` (dev_plan.md §11.5, FR-B1): CSV with columns recognised by their
 * header names (English/Russian/Serbian; separator, encoding, date order and decimal style are
 * detected per file; either one signed amount column or separate debit/credit columns) and
 * OFX/QFX. Bank-specific layouts live in their own plugins (e.g. `import-apibank`).
 *
 * - [BankTransaction.bankAccount] is the file's account column / OFX `ACCTID`, else the file name,
 *   so the host's FR-B3 mapping dialog asks once per account.
 * - [BankTransaction.currency] is the currency column / OFX `CURDEF`, else `XXX` — the host's
 *   mapping dialog then asks which Kalita currency that is.
 * - [BankTransaction.externalId] is the bank's own id when the file has one (`FITID`, an id
 *   column), otherwise a hash of the line plus its ordinal among identical lines (FR-B6).
 */
class StatementFilePlugin : Plugin, StatementImporter {
    override val descriptor = PluginDescriptor(
        id = ID,
        displayName = "Импорт выписки CSV / OFX",
        version = PluginVersion(1, 0, 0),
        requiredApiVersion = PluginVersion(1, 1, 0),
    )

    override val supportedExtensions: Set<String> = setOf("csv", "ofx", "qfx")

    override suspend fun import(fileBytes: ByteArray, fileName: String): List<BankTransaction> =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "ofx", "qfx" -> parseOfxStatement(fileBytes, ID)
            else -> parseCsvStatement(decodeText(fileBytes), ID, fileName)
        }

    companion object {
        const val ID = "import-statement"
    }
}
