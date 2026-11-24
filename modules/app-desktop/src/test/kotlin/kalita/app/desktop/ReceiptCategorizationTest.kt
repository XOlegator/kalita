// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import kalita.core.BudgetItem
import kalita.core.Dataset
import kalita.core.OperationType
import org.junit.jupiter.api.Test

class ReceiptCategorizationTest {
    @Test
    fun `only active unambiguous expense branches are offered`() {
        val dataset = Dataset(
            operationTypes = listOf(OperationType(1, "Expense", 1, -1), OperationType(2, "Income", 4, 1)),
            budgetItems = listOf(
                BudgetItem(1, null, "Expense", null),
                BudgetItem(2, 1, "Food", null),
                BudgetItem(3, 1, "Deleted", null, true),
                BudgetItem(4, 1, "Income", null),
                BudgetItem(5, 4, "Income child", null),
                BudgetItem(6, 3, "Deleted parent", null),
                BudgetItem(7, 99, "Missing parent", null),
                BudgetItem(8, 9, "Cycle", null),
                BudgetItem(9, 8, "Cycle", null),
            ),
        )
        ReceiptCategorizationDialog.activeExpenseItems(dataset).map { it.id }.toSet() shouldBe setOf(1L, 2L)
        ReceiptCategorizationDialog.activeExpenseItems(Dataset(budgetItems = dataset.budgetItems)) shouldBe emptyList()
    }
}