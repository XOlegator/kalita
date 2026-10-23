// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kalita.core.Currency
import kalita.core.Dataset
import kalita.core.Rate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RatePresenterTest {
    private lateinit var presenter: RatePresenter

    private val usd = 11L
    private val eur = 12L
    private val jan1 = LocalDate.of(2006, 1, 1)
    private val jan15 = LocalDate.of(2006, 1, 15)
    private val jan26 = LocalDate.of(2006, 1, 26)
    private val feb1 = LocalDate.of(2006, 2, 1)

    private val dataset = Dataset(
        currencies = listOf(
            Currency(usd, "Доллар", "R01235"),
            Currency(eur, "Евро", "R01239"),
            Currency(13, "Рубль", null),
        ),
        rates = listOf(
            Rate(201, usd, jan1, 28.7825),
            Rate(202, usd, jan26, 27.9805),
            Rate(203, eur, jan1, 34.1850),
            Rate(204, eur, jan15, 33.5000),
            Rate(299, usd, feb1, 28.0000, deleted = true),
        ),
    )

    @BeforeEach
    fun setUp() {
        presenter = RatePresenter()
        presenter.load(dataset)
    }

    @Test
    fun `loads all non-deleted rates`() {
        presenter.state.value.rates shouldHaveSize 4
    }

    @Test
    fun `rates sorted by date descending`() {
        val dates = presenter.state.value.rates.mapNotNull { it.date }
        dates shouldBe dates.sortedDescending()
    }

    @Test
    fun `filter by currency`() {
        presenter.setFilterCurrency(usd)
        presenter.state.value.rates shouldHaveSize 2
        presenter.state.value.rates.all { it.currencyId == usd } shouldBe true
    }

    @Test
    fun `filter by date range`() {
        presenter.setFilterFrom(jan10())
        presenter.setFilterTo(jan20())
        presenter.state.value.rates shouldHaveSize 1
        presenter.state.value.rates[0].id shouldBe 204
    }

    @Test
    fun `combined filter currency and date`() {
        presenter.setFilterCurrency(usd)
        presenter.setFilterFrom(jan20())
        presenter.state.value.rates shouldHaveSize 1
        presenter.state.value.rates[0].id shouldBe 202
    }

    @Test
    fun `null filter currency shows all`() {
        presenter.setFilterCurrency(usd)
        presenter.state.value.rates shouldHaveSize 2
        presenter.setFilterCurrency(null)
        presenter.state.value.rates shouldHaveSize 4
    }

    @Test
    fun `showDeleted includes deleted rates`() {
        presenter.setShowDeleted(true)
        presenter.state.value.rates shouldHaveSize 5
    }

    private fun jan10() = LocalDate.of(2006, 1, 10)
    private fun jan20() = LocalDate.of(2006, 1, 20)
}
