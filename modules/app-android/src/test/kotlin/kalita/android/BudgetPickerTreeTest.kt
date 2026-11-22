// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import kalita.core.BudgetItem
import org.junit.jupiter.api.Test

class BudgetPickerTreeTest {
    private val items = listOf(
        BudgetItem(1, null, "Расходы", 1),
        BudgetItem(2, 1, "Продукты", 1),
        BudgetItem(3, 2, "Молочное", 1),
        BudgetItem(4, 2, "Овощи", 2),
        BudgetItem(5, 1, "Транспорт", 2),
        BudgetItem(6, 1, "Старое", 3, deleted = true),
        BudgetItem(7, null, "Доходы", 2),
    )

    private fun names(rows: List<BudgetPickerRow>) = rows.map { "${"-".repeat(it.depth)}${it.item.name}" }

    @Test
    fun `collapsed tree shows only roots and marks branches`() {
        val rows = BudgetPickerTree.rows(items, emptySet())
        names(rows) shouldBe listOf("Расходы", "Доходы")
        rows.map { it.hasChildren } shouldBe listOf(true, false)
        rows.map { it.expanded } shouldBe listOf(false, false)
    }

    @Test
    fun `expanding opens one level at a time and hides deleted items`() {
        val one = BudgetPickerTree.rows(items, setOf(1L))
        names(one) shouldBe listOf("Расходы", "-Продукты", "-Транспорт", "Доходы")
        val two = BudgetPickerTree.rows(items, setOf(1L, 2L))
        names(two) shouldBe listOf("Расходы", "-Продукты", "--Молочное", "--Овощи", "-Транспорт", "Доходы")
    }

    @Test
    fun `toggle flips membership`() {
        BudgetPickerTree.toggle(setOf(1L), 2) shouldBe setOf(1L, 2L)
        BudgetPickerTree.toggle(setOf(1L, 2L), 2) shouldBe setOf(1L)
    }

    @Test
    fun `selected item starts visible via its ancestors`() {
        val open = BudgetPickerTree.initiallyExpanded(items, 3)
        open shouldBe setOf(1L, 2L)
        BudgetPickerTree.rows(items, open).any { it.item.id == 3L } shouldBe true
        BudgetPickerTree.initiallyExpanded(items, null) shouldBe emptySet()
    }

    @Test
    fun `multiple selected items open all their ancestors without selecting descendants`() {
        val selected = setOf(3L, 5L)
        val open = BudgetPickerTree.initiallyExpanded(items, selected)
        open shouldBe setOf(1L, 2L)
        BudgetPickerTree.rows(items, open).filter { it.item.id in selected }.map { it.item.id }.toSet() shouldBe selected
        BudgetPickerTree.toggle(selected, 2L) shouldBe setOf(2L, 3L, 5L)
        BudgetPickerTree.toggle(selected, 3L) shouldBe setOf(5L)
        BudgetPickerTree.initiallyExpanded(items, emptySet()) shouldBe emptySet()
    }

    @Test
    fun `search keeps matches with their parents and ignores expansion`() {
        names(BudgetPickerTree.rows(items, emptySet(), "овощ")) shouldBe listOf("Расходы", "-Продукты", "--Овощи")
        BudgetPickerTree.rows(items, emptySet(), "xyz") shouldBe emptyList()
        names(BudgetPickerTree.rows(items, emptySet(), "  ПРОДУКТЫ ")) shouldBe listOf("Расходы", "-Продукты")
    }
}
