// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import io.kotest.matchers.shouldBe
import kalita.core.AmountCondition
import kalita.core.JournalFilter
import kalita.core.RefCondition
import kalita.core.RefField
import kalita.core.TextCondition
import kalita.core.TextField
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FilterPresenterTest {
    private val presenter = FilterPresenter()

    @Test
    fun `initial state is the current month`() {
        presenter.state.value.filter shouldBe JournalFilter.CURRENT_MONTH
    }

    @Test
    fun `setFilter replaces the whole filter`() {
        presenter.setFilter(JournalFilter.NONE)
        presenter.state.value.filter shouldBe JournalFilter.NONE
    }

    @Test
    fun `addCondition appends to the condition list`() {
        presenter.setFilter(JournalFilter.NONE)
        val condition = TextCondition(TextField.Comment, "такси")
        presenter.addCondition(condition)
        presenter.state.value.filter.conditions shouldBe listOf(condition)
    }

    @Test
    fun `removeCondition drops the condition at the given index`() {
        presenter.setFilter(JournalFilter.NONE)
        presenter.addCondition(TextCondition(TextField.Comment, "такси"))
        presenter.addCondition(AmountCondition(BigDecimal.TEN, null))
        presenter.removeCondition(0)
        presenter.state.value.filter.conditions shouldBe listOf(AmountCondition(BigDecimal.TEN, null))
    }

    @Test
    fun `replaceCondition swaps the condition at the given index`() {
        presenter.setFilter(JournalFilter.NONE)
        presenter.addCondition(AmountCondition(BigDecimal.ONE, null))
        val replacement = RefCondition(RefField.Currency, setOf(1L, 2L))
        presenter.replaceCondition(0, replacement)
        presenter.state.value.filter.conditions shouldBe listOf(replacement)
    }

    @Test
    fun `reset restores the current month filter`() {
        presenter.setFilter(JournalFilter.NONE)
        presenter.reset()
        presenter.state.value.filter shouldBe JournalFilter.CURRENT_MONTH
    }

    @Test
    fun `clearAll removes every condition`() {
        presenter.addCondition(TextCondition(TextField.Comment, "такси"))
        presenter.clearAll()
        presenter.state.value.filter shouldBe JournalFilter.NONE
    }
}
