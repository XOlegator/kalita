// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.Dataset
import kalita.core.Rate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class RateState(
    val rates: List<Rate> = emptyList(),
    val filterCurrencyId: Long? = null,
    val filterFrom: LocalDate? = null,
    val filterTo: LocalDate? = null,
    val showDeleted: Boolean = false,
)

/**
 * Drives the exchange rates browser (dev_plan.md §5.5, FR-12). Supports filtering by currency
 * and date range.
 */
class RatePresenter {
    private val _state = MutableStateFlow(RateState())
    val state: StateFlow<RateState> = _state.asStateFlow()

    private var dataset: Dataset = Dataset.EMPTY

    fun load(dataset: Dataset) {
        this.dataset = dataset
        recompute()
    }

    fun setFilterCurrency(currencyId: Long?) {
        _state.value = _state.value.copy(filterCurrencyId = currencyId)
        recompute()
    }

    fun setFilterFrom(from: LocalDate?) {
        _state.value = _state.value.copy(filterFrom = from)
        recompute()
    }

    fun setFilterTo(to: LocalDate?) {
        _state.value = _state.value.copy(filterTo = to)
        recompute()
    }

    fun setShowDeleted(show: Boolean) {
        _state.value = _state.value.copy(showDeleted = show)
        recompute()
    }

    private fun recompute() {
        val s = _state.value
        _state.value = s.copy(
            rates = dataset.rates
                .filter { s.showDeleted || !it.deleted }
                .filter { s.filterCurrencyId == null || it.currencyId == s.filterCurrencyId }
                .filter { rate ->
                    val date = rate.date ?: return@filter true
                    (s.filterFrom == null || !date.isBefore(s.filterFrom)) &&
                        (s.filterTo == null || !date.isAfter(s.filterTo))
                }
                .sortedWith(compareByDescending<Rate> { it.date }.thenByDescending { it.id }),
        )
    }
}
