// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import kalita.core.BudgetItem
import kalita.core.Dataset
import kalita.core.OperationType
import org.junit.jupiter.api.Test

class SerbianReceiptDictionaryTest {
    private val root = BudgetItem(1, null, "Расход", null)
    private val milk = BudgetItem(2, 1, "Молоко", null)
    private val bread = BudgetItem(3, 1, "Хлеб", null)
    private val chemicals = BudgetItem(4, 1, "Бытовая химия", null)
    private val expense = OperationType(1, "Расход", 1, -1)

    private fun dataset(vararg items: BudgetItem, types: List<OperationType> = listOf(expense)) =
        Dataset(operationTypes = types, budgetItems = listOf(root, *items))

    @Test
    fun `matches whole words in both scripts and household chemicals`() {
        val data = dataset(milk, bread, chemicals)
        SerbianReceiptDictionary.suggest("МЛЕКО 1л", data) shouldBe milk
        SerbianReceiptDictionary.suggest("MLEKO 1L", data) shouldBe milk
        SerbianReceiptDictionary.suggest("хлеб бели", data) shouldBe bread
        SerbianReceiptDictionary.suggest("DETERDZ\u030cENT", data) shouldBe chemicals
        SerbianReceiptDictionary.suggest("DETERDŽENT", data) shouldBe chemicals
        SerbianReceiptDictionary.suggest("средство за чишћење", data) shouldBe chemicals
        SerbianReceiptDictionary.suggest("mlekoprodukt", data) shouldBe null
        SerbianReceiptDictionary.suggest("mleko2", data) shouldBe null
    }

    @Test
    fun `conflicts and absent or duplicate targets do not guess`() {
        val data = dataset(milk, bread)
        SerbianReceiptDictionary.suggest("mleko hleb", data) shouldBe null
        SerbianReceiptDictionary.suggest("mleko deterdžent", data) shouldBe null
        SerbianReceiptDictionary.suggest("sir", data) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(milk, milk.copy(id = 5))) shouldBe null
        SerbianReceiptDictionary.suggest("unknown", data) shouldBe null
    }

    @Test
    fun `requires an active unambiguous expense branch`() {
        SerbianReceiptDictionary.suggest("mleko", dataset(milk.copy(deleted = true))) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(milk, types = listOf(expense.copy(deleted = true)))) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(milk, types = listOf(expense.copy(budgetRootId = null)))) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(milk, types = listOf(expense, OperationType(2, "Доход", 1, 1)))) shouldBe null
        val deletedParent = BudgetItem(5, 1, "Продукты", null, deleted = true)
        SerbianReceiptDictionary.suggest("mleko", dataset(deletedParent, milk.copy(parentId = 5))) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(milk.copy(parentId = 99))) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(milk.copy(parentId = 2))) shouldBe null
    }
}