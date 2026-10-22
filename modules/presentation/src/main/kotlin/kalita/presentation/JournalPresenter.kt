// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.Aggregate
import kalita.core.Dataset
import kalita.core.JournalColumn
import kalita.core.JournalFilter
import kalita.core.JournalQuery
import kalita.core.JournalRow
import kalita.core.RateService
import kalita.core.SortKey
import kalita.core.aggregate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.LocalDate

data class JournalState(
    val rows: List<JournalRow> = emptyList(),
    val filter: JournalFilter = JournalFilter.CURRENT_MONTH,
    val sort: List<SortKey> = emptyList(),
    val reductionCurrencyId: Long? = null,
    val aggregateFunction: Aggregate = Aggregate.Sum,
    val footerAmount: BigDecimal? = null,
    val footerConvertedAmount: BigDecimal? = null,
    val showDeleted: Boolean = false,
) {
    companion object {
        val EMPTY = JournalState()
    }
}

/**
 * Drives the income/expense journal grid (dev_plan.md §9.8). Accepts commands that change the
 * filter, sort, reduction currency or dataset, and recomputes the visible rows and footer.
 */
class JournalPresenter {
    private val _state = MutableStateFlow(JournalState.EMPTY)
    val state: StateFlow<JournalState> = _state.asStateFlow()

    private var dataset: Dataset = Dataset.EMPTY
    private var rates: RateService = RateService(emptyList(), null)
    private var query: JournalQuery = JournalQuery(dataset, rates)

    fun load(dataset: Dataset) {
        this.dataset = dataset
        this.rates = RateService.of(dataset)
        this.query = JournalQuery(dataset, rates)
        val s = _state.value
        if (s.reductionCurrencyId == null) {
            _state.value = s.copy(reductionCurrencyId = rates.baseCurrencyId)
        }
        recompute()
    }

    fun applyFilter(filter: JournalFilter) {
        _state.value = _state.value.copy(filter = filter)
        recompute()
    }

    fun setSort(sort: List<SortKey>) {
        _state.value = _state.value.copy(sort = sort)
        recompute()
    }

    fun setReductionCurrency(currencyId: Long?) {
        _state.value = _state.value.copy(reductionCurrencyId = currencyId)
        recompute()
    }

    fun setAggregateFunction(fn: Aggregate) {
        _state.value = _state.value.copy(aggregateFunction = fn)
        recomputeFooter()
    }

    fun setShowDeleted(show: Boolean) {
        _state.value = _state.value.copy(showDeleted = show)
        recompute()
    }

    private fun recompute(today: LocalDate = LocalDate.now()) {
        val s = _state.value
        val rows = query.run(s.filter, s.sort, s.reductionCurrencyId, today, s.showDeleted)
        _state.value = s.copy(
            rows = rows,
            footerAmount = aggregate(rows.map { it.values.signedAmount }, s.aggregateFunction),
            footerConvertedAmount = aggregate(rows.map { it.values.convertedAmount }, s.aggregateFunction),
        )
    }

    private fun recomputeFooter() {
        val s = _state.value
        _state.value = s.copy(
            footerAmount = aggregate(s.rows.map { it.values.signedAmount }, s.aggregateFunction),
            footerConvertedAmount = aggregate(s.rows.map { it.values.convertedAmount }, s.aggregateFunction),
        )
    }
}
