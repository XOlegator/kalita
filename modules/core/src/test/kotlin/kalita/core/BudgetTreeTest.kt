// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.shouldBe
import kalita.core.DemoData.EXCHANGE_IN
import kalita.core.DemoData.OPENING_BALANCE
import kalita.core.DemoData.ROOT_EXPENSE
import kalita.core.DemoData.ROOT_INCOME
import kalita.core.DemoData.ROOT_TRANSFER
import org.junit.jupiter.api.Test

class BudgetTreeTest {
    private val demo = BudgetTree(DemoData.dataset.budgetItems)

    @Test
    fun `roots and children follow Ord then id`() {
        demo.roots.map { it.name } shouldBe listOf("Приход", "Перемещение", "Расход")
        demo.children(ROOT_INCOME).map { it.id } shouldBe listOf(OPENING_BALANCE, EXCHANGE_IN)
        demo.children(ROOT_TRANSFER) shouldBe emptyList()

        val tree = BudgetTree(
            listOf(BudgetItem(1, null, "root", 1), BudgetItem(4, 1, "no ord", null), BudgetItem(3, 1, "b", 5), BudgetItem(2, 1, "a", 5)),
        )
        tree.children(1).map { it.id } shouldBe listOf(2L, 3L, 4L)
    }

    @Test
    fun `descendants include the items themselves and everything below`() {
        demo.withDescendants(listOf(ROOT_INCOME)) shouldBe setOf(ROOT_INCOME, OPENING_BALANCE, EXCHANGE_IN)
        demo.withDescendants(listOf(OPENING_BALANCE, 999L)) shouldBe setOf(OPENING_BALANCE, 999L)
        demo.withDescendants(emptyList()) shouldBe emptySet()
    }

    @Test
    fun `path and preorder`() {
        demo.path(EXCHANGE_IN).map { it.id } shouldBe listOf(ROOT_INCOME, EXCHANGE_IN)
        demo.path(999) shouldBe emptyList()
        demo.item(ROOT_EXPENSE)?.name shouldBe "Расход"
        demo.item(null) shouldBe null
        demo.preorder().map { it.name } shouldBe
            listOf("Приход", "Ввод остатков", "Обмен валюты", "Перемещение", "Расход", "Обмен валюты")
    }

    @Test
    fun `broken links neither lose items nor loop`() {
        val tree = BudgetTree(
            listOf(
                BudgetItem(1, 999, "orphan", 1), // parent missing → a root
                BudgetItem(2, 2, "own parent", 2), // self-reference → a root
                BudgetItem(3, 4, "cycle a", 3),
                BudgetItem(4, 3, "cycle b", 4),
            ),
        )
        tree.roots.map { it.id } shouldBe listOf(1L, 2L)
        tree.withDescendants(listOf(3L)) shouldBe setOf(3L, 4L)
        tree.path(3).map { it.id } shouldBe listOf(4L, 3L)
        tree.preorder().map { it.id } shouldBe listOf(1L, 2L, 3L, 4L)
    }
}
