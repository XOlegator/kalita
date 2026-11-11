// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.apibank

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One line of the statement's transaction table, as the bank wrote it. */
data class StatementLine(
    val valueDate: LocalDate,
    val otherPartyAccount: String?,
    val description: String,
    val status: String,
    val amount: BigDecimal,
    val currency: String,
    /** The bank's own conversion column («Amount in RSD») — kept for display, not used for matching. */
    val amountInBase: BigDecimal?,
)

data class Statement(
    val clientName: String,
    /** e.g. `375-5550000007089-69EUR` — the trailing ISO code is the account currency. */
    val accountNumber: String,
    val accountCurrency: String?,
    val from: LocalDate?,
    val to: LocalDate?,
    val lines: List<StatementLine>,
)

/**
 * Layout of API Bank's "AccountData_Transactions_<id>[_CUR].xls" export, as observed on real files
 * (not from any published spec): a label/value header block («Client name», the account number
 * with its currency suffix, «From … To …»), then a table whose header row holds «Value date»,
 * «Other party account», «Description», «Status», «Amount», «Currency», «Amount in RSD». Cells are
 * located by those labels, never by fixed row/column numbers, so a shifted or widened export
 * still parses; every cell is text in the real export, numeric/date cells are accepted too.
 */
internal object ApiBankStatementParser {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/uuuu")
    private val ACCOUNT = Regex("""^\s*(\d{3}-\d{6,}-\d{2})\s*([A-Z]{3})?\s*$""")

    fun parse(sheet: BiffSheet): Statement {
        val headerRow = (0 until sheet.rowCount).firstOrNull { r -> sheet.row(r).any { (_, v) -> v.toString().trim().equals("Value date", ignoreCase = true) } }
            ?: error("не найдена строка заголовков таблицы («Value date»)")
        val columns = sheet.row(headerRow).associate { (c, v) -> v.toString().trim().lowercase() to c }
        fun col(name: String) = columns[name.lowercase()] ?: error("в заголовке таблицы нет колонки «$name»")
        val cDate = col("Value date")
        val cDescription = col("Description")
        val cStatus = col("Status")
        val cAmount = col("Amount")
        val cCurrency = columns["currency"]
        val cOther = columns["other party account"]
        val cBase = columns.entries.firstOrNull { it.key.startsWith("amount in ") }?.value

        var clientName: String? = null
        var accountNumber: String? = null
        var accountCurrency: String? = null
        var from: LocalDate? = null
        var to: LocalDate? = null
        for (r in 0 until headerRow) {
            val row = sheet.row(r)
            for ((i, cell) in row.withIndex()) {
                val label = cell.second.toString().trim()
                val nextCell = row.getOrNull(i + 1)?.second
                val next = nextCell?.toString()?.trim()
                when {
                    label.equals("Client name", ignoreCase = true) -> clientName = next
                    label.equals("From", ignoreCase = true) -> from = nextCell?.let(::parseDate)
                    label.equals("To", ignoreCase = true) -> to = nextCell?.let(::parseDate)
                    else -> ACCOUNT.matchEntire(label)?.let { m ->
                        accountNumber = m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() } ?: "")
                        accountCurrency = m.groupValues[2].takeIf { it.isNotEmpty() }
                    }
                }
            }
        }

        val lines = ArrayList<StatementLine>()
        for (r in headerRow + 1 until sheet.rowCount) {
            val dateCell = sheet.value(r, cDate) ?: continue
            val date = parseDate(dateCell) ?: continue
            val description = sheet.value(r, cDescription)?.toString()?.trim().orEmpty()
            val amountCell = sheet.value(r, cAmount) ?: continue
            val amount = parseAmount(amountCell) ?: continue
            lines += StatementLine(
                valueDate = date,
                otherPartyAccount = cOther?.let { sheet.value(r, it) }?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                description = description,
                status = sheet.value(r, cStatus)?.toString()?.trim().orEmpty(),
                amount = amount,
                currency = (cCurrency?.let { sheet.value(r, it) }?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: accountCurrency ?: "").uppercase(),
                amountInBase = cBase?.let { sheet.value(r, it) }?.let(::parseAmount),
            )
        }
        return Statement(
            clientName = clientName?.takeIf { it.isNotEmpty() } ?: error("в шапке выписки нет «Client name»"),
            accountNumber = accountNumber ?: error("в шапке выписки не найден номер счёта"),
            accountCurrency = accountCurrency,
            from = from,
            to = to,
            lines = lines,
        )
    }

    private fun parseDateOrNull(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim(), DATE) }.getOrNull()

    /** Text `MM/dd/yyyy` as the bank writes it, or an Excel serial day if a cell came through as a number. */
    fun parseDate(cell: Any): LocalDate? = when (cell) {
        is Double -> if (cell > 1) LocalDate.of(1899, 12, 30).plusDays(cell.toLong()) else null
        else -> parseDateOrNull(cell.toString())
    }

    /** `"-16,835.00"` / `"1,650.00"` / `"-0.00"` (thousands separated by commas, dot decimals), or a numeric cell. */
    fun parseAmount(cell: Any): BigDecimal? = when (cell) {
        is Double -> BigDecimal.valueOf(cell).setScale(2, RoundingMode.HALF_UP)
        else -> {
            val t = cell.toString().trim().replace(",", "").replace(" ", "").replace(" ", "")
            if (t.isEmpty()) null else runCatching { BigDecimal(t).setScale(2, RoundingMode.HALF_UP) }.getOrNull()
        }
    }
}
