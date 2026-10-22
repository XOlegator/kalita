// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.Dataset
import kalita.core.RateService
import kalita.core.Saldo
import kalita.core.SaldoService
import kalita.core.SaldoTotal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

data class SaldoState(
    val saldo: Saldo = Saldo(emptyList(), SaldoTotal(BigDecimal.ZERO, BigDecimal.ZERO, emptySet())),
    val reductionCurrencyId: Long? = null,
)

/**
 * Drives the «Остатки» panel (dev_plan.md §5.3, §9.8). Recomputes balances when the dataset
 * or reduction currency changes.
 */
class SaldoPresenter {
    private val _state = MutableStateFlow(SaldoState())
    val state: StateFlow<SaldoState> = _state.asStateFlow()

    private var dataset: Dataset = Dataset.EMPTY
    private var rates: RateService = RateService(emptyList(), null)

    fun load(dataset: Dataset) {
        this.dataset = dataset
        this.rates = RateService.of(dataset)
        val s = _state.value
        if (s.reductionCurrencyId == null) {
            _state.value = s.copy(reductionCurrencyId = rates.baseCurrencyId)
        }
        recompute()
    }

    fun setReductionCurrency(currencyId: Long?) {
        _state.value = _state.value.copy(reductionCurrencyId = currencyId)
        recompute()
    }

    private fun recompute() {
        val s = _state.value
        _state.value = s.copy(saldo = SaldoService.compute(dataset, rates, s.reductionCurrencyId))
    }
}
