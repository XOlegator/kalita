// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import kalita.core.DateCondition
import kalita.core.DateInterval
import kalita.core.JournalFilter
import kalita.core.RefCondition
import kalita.core.RefField
import org.junit.jupiter.api.Test

class JournalChipFilterTest {
    @Test
    fun `default selection is just the current month, same as the desktop default`() {
        JournalChipSelection().toFilter() shouldBe JournalFilter.CURRENT_MONTH
    }

    @Test
    fun `All period drops the date condition entirely, not an unbounded interval`() {
        JournalChipSelection(period = PeriodChip.All).toFilter() shouldBe JournalFilter.NONE
    }

    @Test
    fun `Today maps to DateInterval TODAY`() {
        JournalChipSelection(period = PeriodChip.Today).toFilter() shouldBe
            JournalFilter(listOf(DateCondition(listOf(DateInterval.TODAY))))
    }

    @Test
    fun `each non-empty id set becomes its own RefCondition, empty sets contribute nothing`() {
        val selection = JournalChipSelection(
            period = PeriodChip.All,
            typeIds = setOf(1L, 2L),
            accountIds = emptySet(),
            budgetItemIds = setOf(3L),
            counterpartyIds = setOf(4L, 5L),
        )

        selection.toFilter() shouldBe JournalFilter(
            listOf(
                RefCondition(RefField.OperationType, setOf(1L, 2L)),
                RefCondition(RefField.BudgetItem, setOf(3L)),
                RefCondition(RefField.Counterparty, setOf(4L, 5L)),
            ),
        )
    }

    @Test
    fun `a budget item chip never sets withDescendants - it is a flat pick, no subtree drill`() {
        val condition = JournalChipSelection(period = PeriodChip.All, budgetItemIds = setOf(1L))
            .toFilter().conditions.single() as RefCondition

        condition.withDescendants shouldBe false
    }
}
