// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import kalita.core.BudgetItem
import kalita.core.Dataset
import kalita.core.Operation
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FrequentBudgetItemsTest {
    private fun item(id: Long, deleted: Boolean = false) = BudgetItem(id, null, "item$id", id, deleted)

    private var nextId = 1L
    private fun op(item: Long?, day: Int, deleted: Boolean = false) =
        Operation(nextId++, LocalDate.of(2026, 11, day), BigDecimal.TEN, 1, item, 2, deleted = deleted)

    private val items = listOf(item(1), item(2), item(3), item(4, deleted = true))

    @Test
    fun `recent items are newest first, distinct, and skip deleted rows and items`() {
        val ds = Dataset(
            operations = listOf(op(1, 1), op(2, 5), op(1, 3), op(3, 9, deleted = true), op(4, 8), op(null, 7)),
            budgetItems = items,
        )
        recentBudgetItems(ds).map { it.id } shouldBe listOf(2L, 1L)
        recentBudgetItems(ds, limit = 1).map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `frequent items are most used first, ties go to the more recent, excluded ones are skipped`() {
        val ds = Dataset(
            operations = listOf(op(1, 1), op(1, 2), op(1, 3), op(2, 4), op(2, 5), op(3, 9), op(3, 10, deleted = true), op(4, 6), op(4, 7), op(4, 8), op(4, 9)),
            budgetItems = items,
        )
        // item 1: 3 uses, item 2: 2 uses, item 3: 1 live use, item 4 is deleted.
        frequentBudgetItems(ds).map { it.id } shouldBe listOf(1L, 2L, 3L)
        frequentBudgetItems(ds, exclude = setOf(1L)).map { it.id } shouldBe listOf(2L, 3L)
        frequentBudgetItems(ds, limit = 2).map { it.id } shouldBe listOf(1L, 2L)
    }
}
