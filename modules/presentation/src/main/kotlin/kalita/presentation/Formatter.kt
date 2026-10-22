// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display formatting shared by both shells (dev_plan.md §9.8). All methods are pure and thread-safe.
 * The Russian locale is hardcoded — X-Cash's UI was Russian-only, and localization beyond string
 * resources is out of scope until the roadmap explicitly adds it.
 */
object Formatter {
    private val RU = Locale.forLanguageTag("ru")
    private val DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private val amountSymbols = DecimalFormatSymbols(RU).apply {
        groupingSeparator = ' ' // non-breaking space
        decimalSeparator = ','
    }

    fun date(value: LocalDate): String = DATE_FMT.format(value)

    fun amount(value: BigDecimal, scale: Int = 2): String {
        val fmt = DecimalFormat("#,##0.${"0".repeat(scale)}", amountSymbols)
        fmt.roundingMode = RoundingMode.HALF_UP
        return fmt.format(value)
    }

    fun rate(value: BigDecimal): String {
        val fmt = DecimalFormat("#,##0.####", amountSymbols)
        fmt.roundingMode = RoundingMode.HALF_UP
        return fmt.format(value)
    }
}
