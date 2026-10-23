// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kalita.core.Account
import kalita.core.BudgetItem
import kalita.core.Counterparty
import kalita.core.Currency
import kalita.core.Dataset
import kalita.core.Operation
import kalita.core.OperationType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class DictionaryPresenterTest {
    private lateinit var presenter: DictionaryPresenter

    private val dataset = Dataset(
        accounts = listOf(
            Account(1, "Основной"),
            Account(2, "Муж"),
            Account(3, "Удалён", deleted = true),
        ),
        counterparties = listOf(
            Counterparty(10, "Магазин"),
            Counterparty(11, "Обменники"),
        ),
        currencies = listOf(
            Currency(20, "Рубль", null),
            Currency(21, "Доллар", "R01235"),
        ),
        operationTypes = listOf(
            OperationType(1, "Приход", 100, 1),
            OperationType(2, "Расход", 200, -1),
        ),
        budgetItems = listOf(
            BudgetItem(100, null, "Приход", 1),
            BudgetItem(200, null, "Расход", 2),
            BudgetItem(201, 200, "Продукты", 1),
        ),
        operations = listOf(
            Operation(1000, LocalDate.of(2026, 1, 1), BigDecimal("500"), 20, 201, 2, accountFrom = 1),
            Operation(1001, LocalDate.of(2026, 1, 2), BigDecimal("100"), 20, 201, 2, accountFrom = 2),
        ),
    )

    @BeforeEach
    fun setUp() {
        presenter = DictionaryPresenter()
        presenter.load(dataset)
    }

    @Test
    fun `hides deleted by default`() {
        val state = presenter.state.value
        state.accounts shouldHaveSize 2
        state.accounts.none { it.deleted } shouldBe true
    }

    @Test
    fun `showDeleted reveals deleted items`() {
        presenter.setShowDeleted(true)
        presenter.state.value.accounts shouldHaveSize 3
    }

    @Test
    fun `search filters accounts by name`() {
        presenter.setSearchQuery("муж")
        val state = presenter.state.value
        state.accounts shouldHaveSize 1
        state.accounts[0].name shouldBe "Муж"
    }

    @Test
    fun `search filters counterparties`() {
        presenter.setSearchQuery("магаз")
        presenter.state.value.counterparties shouldHaveSize 1
    }

    @Test
    fun `search filters currencies`() {
        presenter.setSearchQuery("дол")
        presenter.state.value.currencies shouldHaveSize 1
        presenter.state.value.currencies[0].name shouldBe "Доллар"
    }

    @Test
    fun `search filters budget items`() {
        presenter.setSearchQuery("продукт")
        val tree = presenter.state.value.budgetTree
        tree.preorder() shouldHaveSize 1
    }

    @Test
    fun `empty search shows all`() {
        presenter.setSearchQuery("xyz")
        presenter.state.value.accounts.shouldBeEmpty()
        presenter.setSearchQuery("")
        presenter.state.value.accounts shouldHaveSize 2
    }

    @Test
    fun `search is case-insensitive`() {
        presenter.setSearchQuery("ОСНОВНОЙ")
        presenter.state.value.accounts shouldHaveSize 1
    }

    @Test
    fun `reference count for account used in operations`() {
        val count = presenter.operationReferenceCount(DictKind.Account, 1)
        count shouldBe 1
    }

    @Test
    fun `reference count for account used in one operation`() {
        val count = presenter.operationReferenceCount(DictKind.Account, 2)
        count shouldBe 1
    }

    @Test
    fun `reference count for unused account`() {
        val count = presenter.operationReferenceCount(DictKind.Account, 3)
        count shouldBe 0
    }

    @Test
    fun `reference count for budget item includes descendants`() {
        val count = presenter.operationReferenceCount(DictKind.BudgetItem, 200)
        count shouldBe 2
    }

    @Test
    fun `reference count for currency`() {
        val count = presenter.operationReferenceCount(DictKind.CurrencyRef, 20)
        count shouldBe 2
    }

    @Test
    fun `reference count for counterparty`() {
        val count = presenter.operationReferenceCount(DictKind.Counterparty, 10)
        count shouldBe 0
    }
}
