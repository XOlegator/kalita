// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BudgetTreeServiceTest {
    // Tree used by most tests (numbers are Ord values, → means "child of"):
    //   root1(ord=1)
    //     ├─ a(ord=1) → root1
    //     ├─ b(ord=2) → root1
    //     └─ c(ord=3) → root1
    //         └─ d(ord=1) → c
    //   root2(ord=2)
    private val root1 = BudgetItem(1, null, "root1", 1)
    private val root2 = BudgetItem(2, null, "root2", 2)
    private val a = BudgetItem(10, 1, "a", 1)
    private val b = BudgetItem(11, 1, "b", 2)
    private val c = BudgetItem(12, 1, "c", 3)
    private val d = BudgetItem(13, 12, "d", 1)

    private val types = listOf(OperationType(100, "Income", 1, 1))

    private fun service(
        items: List<BudgetItem> = listOf(root1, root2, a, b, c, d),
        operationTypes: List<OperationType> = types,
    ) = BudgetTreeService(Dataset(budgetItems = items, operationTypes = operationTypes))

    @Nested
    inner class MoveUp {
        @Test
        fun `swaps Ord with the previous sibling`() {
            val result = service().moveUp(b.id).shouldBeInstanceOf<MoveResult.Ok>()
            result.changes shouldBe listOf(
                TreeChange.OrdChanged(b.id, 2, 1),
                TreeChange.OrdChanged(a.id, 1, 2),
            )
        }

        @Test
        fun `first sibling returns AlreadyAtBound`() {
            service().moveUp(a.id) shouldBe MoveResult.AlreadyAtBound
        }

        @Test
        fun `sole child returns AlreadyAtBound`() {
            service().moveUp(d.id) shouldBe MoveResult.AlreadyAtBound
        }
    }

    @Nested
    inner class MoveDown {
        @Test
        fun `swaps Ord with the next sibling`() {
            val result = service().moveDown(b.id).shouldBeInstanceOf<MoveResult.Ok>()
            result.changes shouldBe listOf(
                TreeChange.OrdChanged(b.id, 2, 3),
                TreeChange.OrdChanged(c.id, 3, 2),
            )
        }

        @Test
        fun `last sibling returns AlreadyAtBound`() {
            service().moveDown(c.id) shouldBe MoveResult.AlreadyAtBound
        }
    }

    @Nested
    inner class EqualOrd {
        @Test
        fun `equal Ord values trigger renumbering`() {
            val items = listOf(
                BudgetItem(1, null, "x", 5),
                BudgetItem(2, null, "y", 5),
                BudgetItem(3, null, "z", 5),
            )
            // Visual order with equal Ord is by id: 1, 2, 3. Move 2 up → new order: 2, 1, 3.
            val result = service(items, emptyList()).moveUp(2).shouldBeInstanceOf<MoveResult.Ok>()
            val ords = result.changes.filterIsInstance<TreeChange.OrdChanged>().associate { it.itemId to it.new }
            ords[2]!! shouldBe 1
            ords[1]!! shouldBe 2
        }

        @Test
        fun `null Ord values trigger renumbering`() {
            val items = listOf(
                BudgetItem(1, null, "x", null),
                BudgetItem(2, null, "y", null),
            )
            val result = service(items, emptyList()).moveDown(1).shouldBeInstanceOf<MoveResult.Ok>()
            val ords = result.changes.filterIsInstance<TreeChange.OrdChanged>().associate { it.itemId to it.new }
            ords[2]!! shouldBe 1
            ords[1]!! shouldBe 2
        }
    }

    @Nested
    inner class Move {
        @Test
        fun `moves item to a new parent with Ord after existing children`() {
            val result = service().move(a.id, root2.id).shouldBeInstanceOf<MoveResult.Ok>()
            result.changes shouldBe listOf(
                TreeChange.ParentChanged(a.id, root1.id, root2.id),
                TreeChange.OrdChanged(a.id, a.ord, 1),
            )
        }

        @Test
        fun `moves item to root`() {
            val result = service().move(a.id, null).shouldBeInstanceOf<MoveResult.Ok>()
            val parent = result.changes.filterIsInstance<TreeChange.ParentChanged>().single()
            parent shouldBe TreeChange.ParentChanged(a.id, root1.id, null)
            val ord = result.changes.filterIsInstance<TreeChange.OrdChanged>().single()
            ord.itemId shouldBe a.id
            (ord.new > root2.ord!!) shouldBe true
        }

        @Test
        fun `new Ord appends after existing siblings`() {
            val result = service().move(d.id, root1.id).shouldBeInstanceOf<MoveResult.Ok>()
            val ord = result.changes.filterIsInstance<TreeChange.OrdChanged>().single()
            (ord.new > c.ord!!) shouldBe true
        }

        @Test
        fun `moving into a descendant returns WouldCycle`() {
            service().move(c.id, d.id) shouldBe MoveResult.WouldCycle
        }

        @Test
        fun `moving into self returns WouldCycle`() {
            service().move(c.id, c.id) shouldBe MoveResult.WouldCycle
        }

        @Test
        fun `same parent returns SameParent`() {
            service().move(a.id, root1.id) shouldBe MoveResult.SameParent
        }

        @Test
        fun `non-existent target parent returns ItemNotFound`() {
            service().move(a.id, 999) shouldBe MoveResult.ItemNotFound
        }
    }

    @Nested
    inner class Protection {
        @Test
        fun `protected roots cannot be moved up`() {
            service().moveUp(root1.id) shouldBe MoveResult.Protected
        }

        @Test
        fun `protected roots cannot be moved down`() {
            service().moveDown(root1.id) shouldBe MoveResult.Protected
        }

        @Test
        fun `protected roots cannot be reparented`() {
            service().move(root1.id, root2.id) shouldBe MoveResult.Protected
        }

        @Test
        fun `non-protected items can be moved`() {
            service().moveUp(b.id).shouldBeInstanceOf<MoveResult.Ok>()
        }

        @Test
        fun `isProtected reflects operation type roots`() {
            val svc = service()
            svc.isProtected(root1.id) shouldBe true
            svc.isProtected(root2.id) shouldBe false
            svc.isProtected(a.id) shouldBe false
        }

        @Test
        fun `deleted operation type does not protect its root`() {
            val svc = service(operationTypes = listOf(OperationType(100, "Income", 1, 1, deleted = true)))
            svc.isProtected(root1.id) shouldBe false
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `non-existent item returns ItemNotFound`() {
            service().moveUp(999) shouldBe MoveResult.ItemNotFound
            service().moveDown(999) shouldBe MoveResult.ItemNotFound
            service().move(999, null) shouldBe MoveResult.ItemNotFound
        }

        @Test
        fun `orphan item is treated as a root for sibling lookup`() {
            val items = listOf(
                BudgetItem(1, 999, "orphan", 1),
                BudgetItem(2, null, "real root", 2),
            )
            // orphan's parent (999) missing → treated as root, so siblings are [orphan, real root].
            val result = service(items, emptyList()).moveDown(1).shouldBeInstanceOf<MoveResult.Ok>()
            result.changes.filterIsInstance<TreeChange.OrdChanged>().map { it.itemId }.toSet() shouldBe setOf(1L, 2L)
        }
    }
}
