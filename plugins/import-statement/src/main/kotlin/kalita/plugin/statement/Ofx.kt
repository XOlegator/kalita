// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.statement

import kalita.plugin.api.BankTransaction
import java.nio.charset.Charset

/**
 * OFX/QFX (1.x SGML and 2.x XML) → transactions. Tag-based, not a full parser: closing tags are
 * optional in OFX 1.x, so a value runs to the next `<`.
 */
internal fun parseOfxStatement(bytes: ByteArray, source: String): List<BankTransaction> {
    val head = String(bytes, 0, minOf(bytes.size, 600), Charsets.ISO_8859_1)
    val charset = Regex("CHARSET:\\s*(\\w+)").find(head)?.groupValues?.get(1)
    val text = if (charset == "1252") String(bytes, Charset.forName("windows-1252")) else decodeText(bytes, Charset.forName("windows-1252"))
    if (!text.contains("<OFX", ignoreCase = true) && !text.contains("<STMTTRN", ignoreCase = true)) {
        throw IllegalArgumentException("OFX: нет раздела <OFX> — это не выписка в формате OFX/QFX")
    }
    val result = ArrayList<BankTransaction>()
    val seen = HashMap<String, Int>()
    val blocks = Regex("<(?:STMTRS|CCSTMTRS)>(.*?)(?:</(?:STMTRS|CCSTMTRS)>|(?=<(?:STMTRS|CCSTMTRS)>)|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(text).map { it.groupValues[1] }.toList().ifEmpty { listOf(text) }
    for (block in blocks) {
        val currency = tag(block, "CURDEF")?.uppercase() ?: "XXX"
        val account = tag(block, "ACCTID") ?: "ofx"
        val trans = Regex("<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>)|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in trans.findAll(block)) {
            val t = m.groupValues[1]
            val posted = tag(t, "DTPOSTED") ?: throw IllegalArgumentException("OFX: у операции нет DTPOSTED")
            val at = parseDateTime(posted.take(14).substringBefore('[').substringBefore('.'), DateOrder.DayFirst)
                ?: throw IllegalArgumentException("OFX: непонятная дата «$posted»")
            val amount = tag(t, "TRNAMT")?.let(::parseAmount) ?: throw IllegalArgumentException("OFX: у операции нет TRNAMT")
            val name = tag(t, "NAME")
            val memo = tag(t, "MEMO")
            val description = listOfNotNull(name, memo).filter { it.isNotBlank() }.distinct().joinToString(", ")
            val fitId = tag(t, "FITID")
            val base = "$account|${at.toLocalDate()}|${amount.toPlainString()}|$currency|$description"
            val externalId = if (!fitId.isNullOrBlank()) sha("$account|$fitId") else sha("$base|${seen.merge(base, 1, Int::plus)}")
            result += BankTransaction(
                source = source,
                externalId = externalId,
                bankAccount = account,
                postedAt = at.toLocalDate(),
                operationAt = at.takeIf { posted.length > 8 && it.toLocalTime().toSecondOfDay() != 0 },
                amount = amount,
                currency = tag(t, "CURRENCY")?.uppercase() ?: currency,
                description = description,
                merchant = name?.takeIf { it.isNotBlank() },
                mcc = null,
                status = BankTransaction.Status.Posted,
            )
        }
    }
    return result
}

private fun tag(block: String, name: String): String? =
    Regex("<$name>([^<\\r\\n]*)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
        ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.takeIf { it.isNotEmpty() }
