// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.statement

import kalita.plugin.api.BankTransaction
import java.math.BigDecimal
import java.security.MessageDigest

/** Zero-based column indexes; null means that an optional column is absent. */
data class CsvColumns(
    val date: Int,
    val amount: Int? = null,
    val debit: Int? = null,
    val credit: Int? = null,
    val currency: Int? = null,
    val description: Int? = null,
    val account: Int? = null,
    val id: Int? = null,
    val status: Int? = null,
)

/** Parsed nonblank rows and, when recognised, the zero-based header row and suggested mapping. */
data class CsvPreview(val rows: List<List<String>>, val headerRow: Int?, val suggestedColumns: CsvColumns?)

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

private val dateNames = setOf(
    "date", "bookingdate", "postingdate", "posteddate", "transactiondate", "valuedate", "datum", "datumknjizenja", "datumvalute",
    "дата", "датаоперации", "датапроводки", "датаплатежа", "датаоперациипоказаннаявыписке",
)
private val amountNames = setOf("amount", "transactionamount", "iznos", "износ", "сумма", "суммаоперации", "суммаввалютесчета")
private val debitNames = setOf("debit", "withdrawal", "withdrawals", "duguje", "расход", "списание", "дебет", "суммарасхода")
private val creditNames = setOf("credit", "deposit", "deposits", "potrazuje", "potražuje", "приход", "зачисление", "кредит", "суммаприхода")
private val currencyNames = setOf("currency", "ccy", "valuta", "валюта", "валютаоперации", "валютасчета")
private val descriptionNames = setOf(
    "description", "details", "narrative", "memo", "payee", "merchant", "reference", "opis", "svrhaplacanja", "svrha",
    "описание", "назначение", "назначениеплатежа", "детали", "контрагент", "получатель",
)
private val accountNames = setOf("account", "accountnumber", "iban", "racun", "račun", "счет", "счёт", "номерсчета", "номерсчёта", "карта", "номеркарты")
private val idNames = setOf("id", "transactionid", "txid", "referencenumber", "номероперации", "идентификатор", "brojtransakcije")
private val statusNames = setOf("status", "статус", "state")

/** Finds the header row (within the first 30 rows) and maps its columns; null when no row looks like one. */
internal fun findHeader(rows: List<List<String>>): Pair<Int, CsvColumns>? {
    for ((index, row) in rows.take(30).withIndex()) {
        val names = row.map(::norm)
        fun find(set: Set<String>): Int? = names.indexOfFirst { it in set }.takeIf { it >= 0 }
        val date = find(dateNames) ?: continue
        val amount = find(amountNames)
        val debit = find(debitNames)
        val credit = find(creditNames)
        if (amount == null && debit == null && credit == null) continue
        val used = setOfNotNull(date, amount, debit, credit)
        fun other(set: Set<String>): Int? = names.indices.firstOrNull { it !in used && names[it] in set }
        return index to CsvColumns(date, amount, debit, credit, other(currencyNames), other(descriptionNames), other(accountNames), other(idNames), other(statusNames))
    }
    return null
}

private val pendingWords = listOf("pending", "reserved", "hold", "authori", "в обработке", "холд", "резерв", "ожида", "u obradi", "rezerv")

/**
 * CSV statement → transactions. Columns are recognised by header names in English, Russian
 * and Serbian; a file whose header is not recognised is refused with a message naming what is
 * needed rather than guessed at.
 */
internal fun parseCsvStatement(text: String, source: String, fileName: String): List<BankTransaction> {
    val rows = parseCsv(text, detectDelimiter(text))
    val (headerAt, cols) = findHeader(rows)
        ?: throw IllegalArgumentException("CSV: не найдена строка заголовков — нужны колонки даты и суммы (или расход/приход), например Date, Amount, Currency, Description")
    return parseCsvRows(rows, source, fileName, headerAt + 1, cols)
}

internal fun previewCsv(text: String): CsvPreview {
    val rows = parseCsv(text, detectDelimiter(text))
    val header = findHeader(rows)
    return CsvPreview(rows, header?.first, header?.second)
}

internal fun parseCsvRows(rows: List<List<String>>, source: String, fileName: String, dataStartRow: Int, cols: CsvColumns): List<BankTransaction> {
    val data = rows.drop(dataStartRow).withIndex().filter { (_, row) -> row.size > cols.date && row[cols.date].isNotBlank() }
    val order = detectDateOrder(data.map { it.value[cols.date] })
    val fallbackAccount = fileName.substringBeforeLast('.').ifBlank { "statement" }
    val seen = HashMap<String, Int>()
    val result = ArrayList<BankTransaction>()
    for ((n, row) in data) {
        fun cell(i: Int?) = i?.let { row.getOrNull(it) }?.trim().orEmpty()
        val at = parseDateTime(cell(cols.date), order)
            ?: throw IllegalArgumentException("CSV: строка ${dataStartRow + n + 1}: непонятная дата «${cell(cols.date)}»")
        val amount = amountOf(row, cols)
            ?: throw IllegalArgumentException("CSV: строка ${dataStartRow + n + 1}: непонятная сумма")
        val account = cell(cols.account).ifEmpty { fallbackAccount }
        val currency = cell(cols.currency).uppercase().ifEmpty { "XXX" }
        val description = cell(cols.description)
        val bankId = cell(cols.id)
        val base = "$account|${at.toLocalDate()}|${amount.toPlainString()}|$currency|$description"
        val externalId = if (bankId.isNotEmpty()) sha("$account|$bankId") else sha("$base|${seen.merge(base, 1, Int::plus)}")
        val status = cell(cols.status).lowercase()
        result += BankTransaction(
            source = source,
            externalId = externalId,
            bankAccount = account,
            postedAt = at.toLocalDate(),
            operationAt = at.takeIf { it.toLocalTime().toSecondOfDay() != 0 },
            amount = amount,
            currency = currency,
            description = description,
            merchant = description.substringBefore(',').trim().takeIf { it.isNotEmpty() },
            mcc = null,
            status = if (status.isNotEmpty() && pendingWords.any { it in status }) BankTransaction.Status.Pending else BankTransaction.Status.Posted,
        )
    }
    return result
}

internal fun validateCsvMapping(rows: List<List<String>>, dataStartRow: Int, cols: CsvColumns) {
    require(dataStartRow in 0..rows.size) { "CSV: неверный индекс первой строки данных" }
    require(cols.amount != null || cols.debit != null || cols.credit != null) { "CSV: нужна колонка суммы или расхода/прихода" }
    val indices = listOfNotNull(cols.date, cols.amount, cols.debit, cols.credit, cols.currency, cols.description, cols.account, cols.id, cols.status)
    val width = rows.maxOfOrNull { it.size } ?: 0
    require(indices.all { it in 0 until width }) { "CSV: индекс колонки вне диапазона" }
    require(indices.distinct().size == indices.size) { "CSV: одна колонка сопоставлена нескольким полям" }
}

private fun amountOf(row: List<String>, cols: CsvColumns): BigDecimal? {
    cols.amount?.let { i -> row.getOrNull(i)?.let(::parseAmount)?.let { return it } }
    val debit = cols.debit?.let { row.getOrNull(it) }?.let(::parseAmount)?.abs()
    val credit = cols.credit?.let { row.getOrNull(it) }?.let(::parseAmount)?.abs()
    if (debit == null && credit == null) return null
    return (credit ?: BigDecimal.ZERO) - (debit ?: BigDecimal.ZERO)
}

internal fun sha(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
