// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.apibank

import kalita.plugin.api.BankTransaction
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginDescriptor
import kalita.plugin.api.PluginVersion
import kalita.plugin.api.StatementImporter
import java.security.MessageDigest

/**
 * `StatementImporter` for API Bank's `.xls` export (dev_plan.md §11.5, FR-B1). Produces one
 * [BankTransaction] per table line:
 *
 * - [BankTransaction.bankAccount] is the **client name** («OLEG EKHLAKOV»), not the account
 *   number: the same client has one account per currency in this bank and the user keeps them
 *   as one Kalita account («Олег API Bank») with the currency on each operation — so the
 *   FR-B3 mapping is asked once per client, not once per currency account.
 * - [BankTransaction.currency] is the line's own «Currency» column (falling back to the
 *   account number's suffix); the host maps it to a Kalita currency once (FR-B3).
 * - [BankTransaction.externalId] is a hash of account number, date, amount, currency,
 *   description and the line's ordinal among identical lines — the export carries no bank-side
 *   id, and two genuinely identical purchases on one day must stay two transactions (FR-B6).
 * - Only «Executed» lines are [BankTransaction.Status.Posted]; anything else is a hold.
 *
 * Which Kalita account/currency the statement belongs to is not this plugin's decision: it has
 * no settings of its own, the host's mapping dialog covers every importer the same way.
 */
class ApiBankStatementPlugin : Plugin, StatementImporter {
    override val descriptor = PluginDescriptor(
        id = ID,
        displayName = "Импорт выписки API Bank (.xls)",
        version = PluginVersion(1, 0, 0),
        requiredApiVersion = PluginVersion(1, 1, 0),
    )

    override val supportedExtensions: Set<String> = setOf("xls")

    override suspend fun import(fileBytes: ByteArray, fileName: String): List<BankTransaction> =
        toTransactions(ApiBankStatementParser.parse(readXlsFirstSheet(fileBytes)))

    fun toTransactions(statement: Statement): List<BankTransaction> {
        val seen = HashMap<String, Int>()
        return statement.lines.map { line ->
            val base = "${statement.accountNumber}|${line.valueDate}|${line.amount.toPlainString()}|${line.currency}|${line.description}"
            val ordinal = seen.merge(base, 1, Int::plus)!!
            BankTransaction(
                source = ID,
                externalId = sha256("$base|$ordinal"),
                bankAccount = statement.clientName,
                postedAt = line.valueDate,
                operationAt = null,
                amount = line.amount,
                currency = line.currency,
                description = line.description,
                merchant = line.description.substringBefore(',').trim().takeIf { it.isNotEmpty() },
                mcc = null,
                status = if (line.status.equals("Executed", ignoreCase = true)) BankTransaction.Status.Posted else BankTransaction.Status.Pending,
            )
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        const val ID = "import-apibank"
    }
}
