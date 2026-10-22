// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FormatterTest {
    @Test
    fun `date formats as dd-MM-yyyy`() {
        Formatter.date(LocalDate.of(2026, 1, 6)) shouldBe "06.01.2026"
    }

    @Test
    fun `amount with space as thousands separator and comma as decimal`() {
        Formatter.amount(BigDecimal("64920.00")) shouldBe "64 920,00"
    }

    @Test
    fun `amount rounds to 2 decimal places`() {
        Formatter.amount(BigDecimal("1234.5678")) shouldBe "1 234,57"
    }

    @Test
    fun `rate with up to 4 decimal places`() {
        Formatter.rate(BigDecimal("28.7825")) shouldBe "28,7825"
    }

    @Test
    fun `rate trims trailing zeros`() {
        Formatter.rate(BigDecimal("1.00")) shouldBe "1"
    }

    @Test
    fun `zero amount`() {
        Formatter.amount(BigDecimal.ZERO) shouldBe "0,00"
    }
}
