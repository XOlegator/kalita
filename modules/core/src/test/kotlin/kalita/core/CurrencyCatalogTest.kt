// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class CurrencyCatalogTest {
    @Test
    fun `a name suggests its ISO code`() {
        CurrencyCatalog.codeFor("Сербский динар") shouldBe "RSD"
        CurrencyCatalog.codeFor("  белорусский   рубль ") shouldBe "BYN"
        CurrencyCatalog.codeFor("Йена") shouldBe "JPY"
        CurrencyCatalog.codeFor("rsd") shouldBe "RSD"
        CurrencyCatalog.codeFor("Тугрик") shouldBe null
        CurrencyCatalog.codeFor("") shouldBe null
        CurrencyCatalog.codeFor(null) shouldBe null
    }

    @Test
    fun `an entered code always wins over the suggestion`() {
        CurrencyCatalog.codeOrSuggestion("Доллар", " R01235 ") shouldBe "R01235"
        CurrencyCatalog.codeOrSuggestion("Доллар", "  ") shouldBe "USD"
        CurrencyCatalog.codeOrSuggestion("Тугрик", null) shouldBe null
    }

    @Test
    fun `the base currency is the unquoted one the journal uses most when several have no rates`() {
        val day = LocalDate.of(2026, 11, 7)
        fun op(id: Long, currency: Long, deleted: Boolean = false) = Operation(id, day, BigDecimal.ONE, currency, null, null, deleted = deleted)
        val currencies = listOf(Currency(12, "Рубль", null), Currency(10, "Доллар", "R01235"), Currency(18, "Белорусский рубль", "BYR"), Currency(20, "RSD", null))
        val rates = listOf(Rate(1, 10, day, 80.0))

        // Three currencies without rates; the rouble carries the journal.
        Dataset(operations = listOf(op(1, 12), op(2, 12), op(3, 20), op(4, 10)), currencies = currencies, rates = rates).detectBaseCurrency() shouldBe 12L
        // Deleted operations do not count, and a tie singles nobody out.
        Dataset(operations = listOf(op(1, 12), op(2, 12, deleted = true), op(3, 20)), currencies = currencies, rates = rates).detectBaseCurrency() shouldBe null
        // Nothing in the journal yet.
        Dataset(currencies = currencies, rates = rates).detectBaseCurrency() shouldBe null
        // Only one of them is used at all.
        Dataset(operations = listOf(op(1, 20)), currencies = currencies, rates = rates).detectBaseCurrency() shouldBe 20L
    }

    @Test
    fun `a base currency chosen by the user comes before every rule, while it is still a live currency`() {
        val day = LocalDate.of(2026, 11, 7)
        val currencies = listOf(Currency(12, "Рубль", null), Currency(10, "Доллар", "R01235"), Currency(20, "RSD", null), Currency(30, "Старая", null, deleted = true))
        val dataset = Dataset(currencies = currencies, rates = listOf(Rate(1, 10, day, 80.0)))

        dataset.detectBaseCurrency() shouldBe null
        dataset.withBaseCurrency(20).detectBaseCurrency() shouldBe 20L
        dataset.withBaseCurrency(10).detectBaseCurrency() shouldBe 10L // even one that has rates: the user said so
        dataset.withBaseCurrency(30).explicitBaseCurrency() shouldBe null
        dataset.withBaseCurrency(99).detectBaseCurrency() shouldBe null
        dataset.withBaseCurrency(20).withBaseCurrency(null).explicitBaseCurrency() shouldBe null
        RateService.of(dataset.withBaseCurrency(20)).baseCurrencyId shouldBe 20L
    }
}
