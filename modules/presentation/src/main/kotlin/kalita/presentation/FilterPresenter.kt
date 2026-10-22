// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.FilterCondition
import kalita.core.JournalFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FilterState(
    val filter: JournalFilter = JournalFilter.CURRENT_MONTH,
)

/**
 * Drives the «Фильтр» panel (dev_plan.md §6.3, §9.8). Manages the set of active conditions;
 * the shell applies the resulting [JournalFilter] to [JournalPresenter.applyFilter].
 */
class FilterPresenter {
    private val _state = MutableStateFlow(FilterState())
    val state: StateFlow<FilterState> = _state.asStateFlow()

    fun setFilter(filter: JournalFilter) {
        _state.value = FilterState(filter)
    }

    fun addCondition(condition: FilterCondition) {
        val current = _state.value.filter.conditions
        _state.value = FilterState(JournalFilter(current + condition))
    }

    fun removeCondition(index: Int) {
        val current = _state.value.filter.conditions.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _state.value = FilterState(JournalFilter(current))
        }
    }

    fun replaceCondition(index: Int, condition: FilterCondition) {
        val current = _state.value.filter.conditions.toMutableList()
        if (index in current.indices) {
            current[index] = condition
            _state.value = FilterState(JournalFilter(current))
        }
    }

    fun reset() {
        _state.value = FilterState(JournalFilter.CURRENT_MONTH)
    }

    fun clearAll() {
        _state.value = FilterState(JournalFilter.NONE)
    }
}
