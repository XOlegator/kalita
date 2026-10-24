// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kalita.core.Aggregate
import kalita.core.JournalFilter
import kalita.core.SortKey
import kalita.core.JournalColumn
import org.junit.jupiter.api.Test

class JournalPresenterTest {
    private val presenter = JournalPresenter().also { it.load(DemoPresentationData.dataset) }

    @Test
    fun `initial load with current month filter produces no rows for historic data`() {
        // Default filter is CURRENT_MONTH; demo data is from 2006, so no rows match.
        presenter.state.value.rows.size shouldBe 0
    }

    @Test
    fun `clearing filter shows all non-deleted operations`() {
        presenter.applyFilter(JournalFilter.NONE)
        presenter.state.value.rows.shouldNotBeEmpty()
    }

    @Test
    fun `reduction currency defaults to the base currency`() {
        presenter.state.value.reductionCurrencyId shouldBe DemoPresentationData.RUB
    }

    @Test
    fun `clearing filter row count matches non-deleted operations`() {
        presenter.applyFilter(JournalFilter.NONE)
        presenter.state.value.rows.size shouldBe DemoPresentationData.dataset.operations.count { !it.deleted }
    }

    @Test
    fun `sort changes the row order`() {
        presenter.applyFilter(JournalFilter.NONE)
        val unsorted = presenter.state.value.rows.map { it.operation.id }
        presenter.setSort(listOf(SortKey(JournalColumn.Amount, descending = true)))
        val sorted = presenter.state.value.rows.map { it.operation.id }
        sorted shouldNotBe unsorted
    }

    @Test
    fun `footer reflects the aggregate function`() {
        presenter.applyFilter(JournalFilter.NONE)
        val sumFooter = presenter.state.value.footerAmount
        presenter.setAggregateFunction(Aggregate.Count)
        val countFooter = presenter.state.value.footerAmount
        countFooter shouldNotBe sumFooter
    }

    @Test
    fun `changing reduction currency recomputes`() {
        presenter.applyFilter(JournalFilter.NONE)
        val rub = presenter.state.value.footerConvertedAmount
        presenter.setReductionCurrency(DemoPresentationData.USD)
        val usd = presenter.state.value.footerConvertedAmount
        usd shouldNotBe rub
    }

    @Test
    fun `text filter narrows rows to a matching account name`() {
        presenter.applyFilter(JournalFilter.NONE)
        val all = presenter.state.value.rows.size
        presenter.setTextFilter("Муж")
        presenter.state.value.rows.size shouldBe 3
        presenter.state.value.rows.size shouldNotBe all
    }

    @Test
    fun `clearing the text filter restores all rows`() {
        presenter.applyFilter(JournalFilter.NONE)
        val all = presenter.state.value.rows.size
        presenter.setTextFilter("Муж")
        presenter.setTextFilter("")
        presenter.state.value.rows.size shouldBe all
    }
}
