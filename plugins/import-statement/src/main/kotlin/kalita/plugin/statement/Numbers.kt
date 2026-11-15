// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.statement

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Order of day/month in a numeric date such as 03/04/2026, decided once per file. */
internal enum class DateOrder { DayFirst, MonthFirst }

/**
 * Parses a bank amount written in any common style: "-1 234,56", "1,234.56", "(12.50)",
 * "12.50-", "RSD 1.234,56", "1'234.56". Returns null for blank/unparseable text.
 * A lone separator followed by exactly three digits is read as a thousands separator
 * ("1,234" = 1234), anything else as the decimal point.
 */
internal fun parseAmount(raw: String): BigDecimal? {
    var s = raw.trim().replace('−', '-').replace(" ", "").replace(" ", "")
    if (s.isEmpty()) return null
    var negative = false
    if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
    s = s.filter { it.isDigit() || it in ".,-+'" }
    if (s.endsWith("-")) { negative = !negative; s = s.dropLast(1) }
    if (s.startsWith("-")) { negative = !negative; s = s.drop(1) } else if (s.startsWith("+")) s = s.drop(1)
    s = s.replace("'", "")
    if (s.isEmpty() || s.any { it == '-' || it == '+' }) return null
    val lastDot = s.lastIndexOf('.')
    val lastComma = s.lastIndexOf(',')
    val decimalAt = when {
        lastDot >= 0 && lastComma >= 0 -> maxOf(lastDot, lastComma)
        lastDot >= 0 -> singleSeparator(s, '.', lastDot)
        lastComma >= 0 -> singleSeparator(s, ',', lastComma)
        else -> -1
    }
    val digits = if (decimalAt < 0) s.filter { it.isDigit() }
    else s.substring(0, decimalAt).filter { it.isDigit() } + "." + s.substring(decimalAt + 1).filter { it.isDigit() }
    if (digits.isEmpty() || digits == ".") return null
    val value = digits.toBigDecimalOrNull() ?: return null
    return if (negative) value.negate() else value
}

private fun singleSeparator(s: String, sep: Char, last: Int): Int {
    val count = s.count { it == sep }
    val tail = s.length - last - 1
    return if (count > 1 || tail == 3) -1 else last
}

private val numericDate = Regex("""^\s*(\d{1,4})[./\-](\d{1,2})[./\-](\d{1,4})\.?(?:[ T,]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?.*$""")
private val compactDate = Regex("""^\s*(\d{4})(\d{2})(\d{2})(?:(\d{2})(\d{2})(\d{2})?)?.*$""")

/** Reads a date(-time) in yyyy-MM-dd, dd.MM.yyyy, dd/MM/yy, MM/dd/yyyy or yyyyMMdd[HHmmss] form. */
internal fun parseDateTime(raw: String, order: DateOrder): LocalDateTime? {
    val compact = compactDate.matchEntire(raw)
    if (compact != null && !raw.trim().contains(Regex("[./:-]"))) {
        val (y, m, d) = compact.destructured
        return build(y.toInt(), m.toInt(), d.toInt(), compact.groupValues[4], compact.groupValues[5], compact.groupValues[6])
    }
    val m = numericDate.matchEntire(raw) ?: return null
    val (a, b, c) = m.destructured
    val (year, month, day) = when {
        a.length == 4 -> Triple(a.toInt(), b.toInt(), c.toInt())
        order == DateOrder.DayFirst -> Triple(fullYear(c), b.toInt(), a.toInt())
        else -> Triple(fullYear(c), a.toInt(), b.toInt())
    }
    return build(year, month, day, m.groupValues[4], m.groupValues[5], m.groupValues[6])
}

private fun fullYear(text: String): Int = text.toInt().let { if (text.length <= 2) 2000 + it else it }

private fun build(y: Int, mo: Int, d: Int, h: String, mi: String, s: String): LocalDateTime? = runCatching {
    LocalDate.of(y, mo, d).atTime(h.toIntOrNull() ?: 0, mi.toIntOrNull() ?: 0, s.toIntOrNull() ?: 0)
}.getOrNull()

/** Decides day/month order from the file's own dates: a field > 12 gives it away; default is day first. */
internal fun detectDateOrder(samples: List<String>): DateOrder {
    for (s in samples) {
        val m = numericDate.matchEntire(s) ?: continue
        if (m.groupValues[1].length == 4) continue
        if (m.groupValues[1].toInt() > 12) return DateOrder.DayFirst
        if (m.groupValues[2].toInt() > 12) return DateOrder.MonthFirst
    }
    return DateOrder.DayFirst
}
