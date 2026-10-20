// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.shouldBe
import kalita.core.DemoData.EUR
import kalita.core.DemoData.RUB
import kalita.core.DemoData.USD
import kalita.core.DemoData.jan1
import kalita.core.DemoData.jan26
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class RateServiceTest {
    private val rates = RateService.of(DemoData.dataset)

    @Test
    fun `the base currency is the only live currency without rates`() {
        DemoData.dataset.detectBaseCurrency() shouldBe RUB
        rates.baseCurrencyId shouldBe RUB
    }

    @Test
    fun `base currency detection gives up when ambiguous`() {
        Dataset(currencies = listOf(Currency(1, "A", null), Currency(2, "B", null))).detectBaseCurrency() shouldBe null
        Dataset.EMPTY.detectBaseCurrency() shouldBe null
        // Deleted currencies and deleted rates do not count.
        Dataset(
            currencies = listOf(Currency(1, "A", null), Currency(2, "B", null), Currency(3, "C", null, deleted = true)),
            rates = listOf(Rate(1, 2, jan1, 2.0), Rate(2, 1, jan1, 3.0, deleted = true)),
        ).detectBaseCurrency() shouldBe 1L
    }

    @Test
    fun `rate on a date is the last one on or before it`() {
        rates.rate(USD, jan1) shouldBe BigDecimal("28.7825")
        rates.rate(USD, jan26.minusDays(1)) shouldBe BigDecimal("28.7825")
        rates.rate(USD, jan26) shouldBe BigDecimal("27.9805")
        rates.rate(USD, LocalDate.of(2026, 10, 19)) shouldBe BigDecimal("26.9351")
        rates.latest(USD) shouldBe BigDecimal("26.9351")
    }

    @Test
    fun `base currency is always 1, unknown inputs give no rate`() {
        rates.rate(RUB, null) shouldBe BigDecimal.ONE
        rates.rate(USD, jan1.minusDays(1)) shouldBe null
        rates.rate(USD, null) shouldBe null
        rates.rate(null, jan1) shouldBe null
        rates.rate(999, jan1) shouldBe null
    }

    @Test
    fun `on the same date the row with the higher id wins`() {
        val service = RateService(listOf(Rate(5, 1, jan1, 2.0), Rate(4, 1, jan1, 3.0), Rate(6, 1, null, 9.0)), 0)
        service.rate(1, jan1) shouldBe BigDecimal("2.0")
    }

    @Test
    fun `cross rate divides by the target currency's rate`() {
        rates.cross(USD, RUB, jan26) shouldBe BigDecimal("27.9805")
        rates.cross(RUB, RUB, jan26) shouldBe BigDecimal.ONE
        rates.cross(EUR, USD, jan26)!!.setScale(6, java.math.RoundingMode.HALF_UP) shouldBe BigDecimal("1.211737")
        rates.cross(USD, EUR, jan1.minusDays(1)) shouldBe null
        rates.latestCross(USD, RUB) shouldBe BigDecimal("26.9351")
        RateService(listOf(Rate(1, 1, jan1, 0.0)), 0).cross(0, 1, jan1) shouldBe null
    }
}
