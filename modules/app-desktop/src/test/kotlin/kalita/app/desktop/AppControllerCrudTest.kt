// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.SubmitResult
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.LocalDate

class AppControllerCrudTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var ctrl: AppController

    @BeforeEach
    fun setUp() {
        val dbFile = createDemoFixture(tempDir.resolve("Data.mdb").toFile())
        val settings = AppSettings(tempDir.resolve("config"))
        ctrl = AppController(settings)
        ctrl.openDatabase(dbFile)
    }

    private fun ready(): AppState.Ready = ctrl.state.value as AppState.Ready

    // --- Accounts ---

    @Test
    fun `addAccount creates a new account visible in dataset`() {
        val result = ctrl.addAccount("Новый счёт")
        result.shouldBeInstanceOf<SubmitResult.Applied>()
        val accounts = ready().dataset.accounts
        accounts.any { it.name == "Новый счёт" && !it.deleted } shouldBe true
    }

    @Test
    fun `updateAccount changes account name`() {
        val accountId = ready().dataset.accounts.first().id
        ctrl.updateAccount(accountId, "Переименованный")
        ready().dataset.accounts.first { it.id == accountId }.name shouldBe "Переименованный"
    }

    @Test
    fun `softDeleteAccount marks account as deleted`() {
        val accountId = ready().dataset.accounts.first().id
        ctrl.softDeleteAccount(accountId)
        ready().dataset.accounts.first { it.id == accountId }.deleted shouldBe true
    }

    @Test
    fun `restoreAccount undeletes account`() {
        val accountId = ready().dataset.accounts.first().id
        ctrl.softDeleteAccount(accountId)
        ctrl.restoreAccount(accountId)
        ready().dataset.accounts.first { it.id == accountId }.deleted shouldBe false
    }

    // --- Counterparties ---

    @Test
    fun `addCounterparty creates a new entry`() {
        ctrl.addCounterparty("Продавец")
        ready().dataset.counterparties.any { it.name == "Продавец" } shouldBe true
    }

    // --- Currencies ---

    @Test
    fun `addCurrency creates a new currency with cbId`() {
        ctrl.addCurrency("Евро", "R01239")
        val eur = ready().dataset.currencies.firstOrNull { it.name == "Евро" }
        eur shouldBe eur?.copy()
        eur!!.cbId shouldBe "R01239"
    }

    @Test
    fun `updateCurrency changes name and cbId`() {
        ctrl.addCurrency("Тест", null)
        val id = ready().dataset.currencies.first { it.name == "Тест" }.id
        ctrl.updateCurrency(id, "Тест2", "R99999")
        val updated = ready().dataset.currencies.first { it.id == id }
        updated.name shouldBe "Тест2"
        updated.cbId shouldBe "R99999"
    }

    // --- Budget items ---

    @Test
    fun `addBudgetItem creates a root item`() {
        ctrl.addBudgetItem(null, "Новая статья")
        ready().dataset.budgetItems.any { it.name == "Новая статья" && it.parentId == null } shouldBe true
    }

    @Test
    fun `addBudgetItem creates a child item`() {
        ctrl.addBudgetItem(null, "Родитель")
        val parentId = ready().dataset.budgetItems.first { it.name == "Родитель" }.id
        ctrl.addBudgetItem(parentId, "Дочерняя")
        ready().dataset.budgetItems.any { it.name == "Дочерняя" && it.parentId == parentId } shouldBe true
    }

    @Test
    fun `updateBudgetItem changes name`() {
        ctrl.addBudgetItem(null, "Статья")
        val id = ready().dataset.budgetItems.first { it.name == "Статья" }.id
        ctrl.updateBudgetItem(id, "Статья (изм.)")
        ready().dataset.budgetItems.first { it.id == id }.name shouldBe "Статья (изм.)"
    }

    // --- Rates ---

    @Test
    fun `addRate creates a new rate`() {
        ctrl.addCurrency("Доллар", "R01235")
        val currId = ready().dataset.currencies.first { it.name == "Доллар" }.id
        val result = ctrl.addRate(currId, LocalDate.of(2026, 10, 1), 95.5)
        result.shouldBeInstanceOf<SubmitResult.Applied>()
        val rate = ready().dataset.rates.firstOrNull { it.currencyId == currId }
        rate?.value shouldBe 95.5
    }

    @Test
    fun `hardDeleteRate removes the rate`() {
        ctrl.addCurrency("Фунт", null)
        val currId = ready().dataset.currencies.first { it.name == "Фунт" }.id
        ctrl.addRate(currId, LocalDate.of(2026, 10, 1), 120.0)
        val rateId = ready().dataset.rates.first { it.currencyId == currId }.id
        ctrl.hardDeleteRate(rateId)
        ready().dataset.rates.none { it.id == rateId } shouldBe true
    }

    // --- Read-only guard ---

    @Test
    fun `submit on no database returns Failed`() {
        val empty = AppController(AppSettings(tempDir.resolve("config2")))
        val result = empty.addAccount("Test")
        result.shouldBeInstanceOf<SubmitResult.Failed>()
    }
}
