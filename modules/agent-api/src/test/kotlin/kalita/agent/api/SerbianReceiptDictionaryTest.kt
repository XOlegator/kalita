// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import kalita.core.BudgetItem
import kalita.core.Dataset
import kalita.core.OperationType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SerbianReceiptDictionaryTest {
    private val root = BudgetItem(1, null, "Расход", null)
    private val milk = BudgetItem(2, 1, "Молоко", null)
    private val bread = BudgetItem(3, 1, "Хлеб", null)
    private val chemicals = BudgetItem(4, 1, "Бытовая химия", null)
    private val expense = OperationType(1, "Расход", 1, -1)

    private fun dataset(vararg items: BudgetItem, types: List<OperationType> = listOf(expense)) =
        Dataset(operationTypes = types, budgetItems = listOf(root, *items))

    @Test
    fun `built in entries can be changed disabled and reset without losing other settings`(@TempDir dir: Path) {
        val store = ReceiptCategorizationStore(dir.toFile())
        val data = dataset(milk, bread)
        val original = store.builtInDictionary().first { it.text == "mleko" }
        original.meaning shouldBe original.defaultMeaning
        store.replaceRules(listOf(ReceiptCategoryRule("shop", null, milk.id)))
        store.replaceDictionary(listOf(ReceiptDictionaryEntry("custom", bread.id)))
        store.setBuiltInDictionaryEntry("mleko", "хлеб")
        ReceiptCategorizationStore(dir.toFile()).dictionaryHint("mleko", data) shouldBe bread.id
        store.dictionaryHint("mleko hleb", data) shouldBe bread.id
        store.setBuiltInDictionaryEntry("hleb", "молоко")
        store.dictionaryHint("mleko hleb", data) shouldBe null
        store.setBuiltInDictionaryEntry("mleko", null)
        store.replaceRules(store.rules())
        store.replaceDictionary(store.dictionary())
        ReceiptCategorizationStore(dir.toFile()).dictionaryHint("mleko", data) shouldBe null
        store.builtInDictionary().first { it.text == "mleko" }.defaultMeaning shouldBe original.defaultMeaning
        store.resetBuiltInDictionaryEntry("mleko")
        store.dictionaryHint("mleko", data) shouldBe milk.id
        store.rules().size shouldBe 1
        store.dictionary().size shouldBe 1
    }

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
    fun `food falls back to a single active groceries item without hiding ambiguity`() {
        val groceries = BudgetItem(6, 1, "Продукты", null)
        SerbianReceiptDictionary.suggest("млеко", dataset(groceries)) shouldBe groceries
        SerbianReceiptDictionary.suggest("hleb", dataset(groceries, bread)) shouldBe bread
        SerbianReceiptDictionary.suggest("sir", dataset(groceries)) shouldBe groceries
        SerbianReceiptDictionary.suggest("deterdžent", dataset(groceries)) shouldBe null
        SerbianReceiptDictionary.suggest("mleko hleb", dataset(groceries)) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(groceries, groceries.copy(id = 7))) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(groceries, milk, milk.copy(id = 7))) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(groceries.copy(deleted = true))) shouldBe null
        SerbianReceiptDictionary.suggest("mleko", dataset(groceries, types = listOf(expense, OperationType(2, "Доход", 1, 1)))) shouldBe null
    }

    @Test
    fun `overridden food meaning does not fall back to groceries`(@TempDir dir: Path) {
        val groceries = BudgetItem(6, 1, "Продукты", null)
        val store = ReceiptCategorizationStore(dir.toFile())
        store.setBuiltInDictionaryEntry("mleko", "хлеб")
        store.dictionaryHint("mleko", dataset(groceries)) shouldBe null
        store.dictionaryHint("mleko", dataset(groceries, bread)) shouldBe bread.id
        store.dictionaryHint("hleb", dataset(groceries)) shouldBe groceries.id
        store.dictionaryHint("mleko hleb", dataset(groceries)) shouldBe null
        store.resetBuiltInDictionaryEntry("mleko")
        store.dictionaryHint("mleko", dataset(groceries)) shouldBe groceries.id
    }

    @Test
    fun `additional common foods match in both scripts without guessing conflicts`() {
        val groceries = BudgetItem(6, 1, "Продукты", null)
        val meat = BudgetItem(7, 1, "Мясо", null)
        val data = dataset(groceries, meat)
        listOf("voće", "ВОЋЕ", "povrće", "ПОВРЋЕ", "paradajz", "КРАСТАВЦИ", "jogurt", "путер", "testenina")
            .forEach { SerbianReceiptDictionary.suggest(it, data) shouldBe groceries }
        SerbianReceiptDictionary.suggest("МЕСО", data) shouldBe meat
        SerbianReceiptDictionary.suggest("meso voće", data) shouldBe null
        SerbianReceiptDictionary.suggest("voćni", data) shouldBe null
        SerbianReceiptDictionary.suggest("meso", dataset(meat, meat.copy(id = 8), groceries)) shouldBe null
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