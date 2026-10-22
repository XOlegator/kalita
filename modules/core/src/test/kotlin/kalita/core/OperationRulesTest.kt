// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class OperationRulesTest {
    private val rules = OperationRules(DemoData.dataset)
    private val today = LocalDate.of(2026, 10, 22)

    private fun validExpenseDraft() = OperationDraft(
        date = today,
        amount = BigDecimal("150.00"),
        currencyId = DemoData.RUB,
        budgetItemId = DemoData.EXCHANGE_OUT,
        typeId = DemoData.EXPENSE,
        accountFrom = DemoData.MAIN,
    )

    private fun validIncomeDraft() = OperationDraft(
        date = today,
        amount = BigDecimal("500"),
        currencyId = DemoData.RUB,
        budgetItemId = DemoData.OPENING_BALANCE,
        typeId = DemoData.INCOME,
        accountTo = DemoData.MAIN,
    )

    private fun validTransferDraft() = OperationDraft(
        date = today,
        amount = BigDecimal("200"),
        currencyId = DemoData.RUB,
        budgetItemId = DemoData.ROOT_TRANSFER,
        typeId = DemoData.TRANSFER,
        accountFrom = DemoData.MAIN,
        accountTo = DemoData.HUSBAND,
    )

    @Nested
    inner class Validation {
        @Test
        fun `valid expense draft passes`() {
            rules.validate(validExpenseDraft()).shouldBeEmpty()
        }

        @Test
        fun `valid income draft passes`() {
            rules.validate(validIncomeDraft()).shouldBeEmpty()
        }

        @Test
        fun `valid transfer draft passes`() {
            rules.validate(validTransferDraft()).shouldBeEmpty()
        }

        @Test
        fun `empty draft reports all required fields`() {
            val errors = rules.validate(OperationDraft())
            errors shouldContain ValidationError.MissingDate
            errors shouldContain ValidationError.MissingAmount
            errors shouldContain ValidationError.MissingCurrency
            errors shouldContain ValidationError.MissingOperationType
            errors shouldContain ValidationError.MissingBudgetItem
        }

        @Test
        fun `zero amount is rejected`() {
            val draft = validExpenseDraft().copy(amount = BigDecimal.ZERO)
            rules.validate(draft) shouldContain ValidationError.NonPositiveAmount
        }

        @Test
        fun `negative amount is rejected`() {
            val draft = validExpenseDraft().copy(amount = BigDecimal("-10"))
            rules.validate(draft) shouldContain ValidationError.NonPositiveAmount
        }

        @Test
        fun `expense without accountFrom is rejected`() {
            val draft = validExpenseDraft().copy(accountFrom = null)
            rules.validate(draft) shouldContain ValidationError.MissingAccountFrom
        }

        @Test
        fun `income without accountTo is rejected`() {
            val draft = validIncomeDraft().copy(accountTo = null)
            rules.validate(draft) shouldContain ValidationError.MissingAccountTo
        }

        @Test
        fun `transfer without accountFrom is rejected`() {
            val draft = validTransferDraft().copy(accountFrom = null)
            rules.validate(draft) shouldContain ValidationError.MissingAccountFrom
        }

        @Test
        fun `transfer without accountTo is rejected`() {
            val draft = validTransferDraft().copy(accountTo = null)
            rules.validate(draft) shouldContain ValidationError.MissingAccountTo
        }

        @Test
        fun `expense accountTo is not required`() {
            val draft = validExpenseDraft().copy(accountTo = null)
            rules.validate(draft) shouldNotContain ValidationError.MissingAccountTo
        }

        @Test
        fun `income accountFrom is not required`() {
            val draft = validIncomeDraft().copy(accountFrom = null)
            rules.validate(draft) shouldNotContain ValidationError.MissingAccountFrom
        }

        @Test
        fun `budget item from wrong subtree is rejected`() {
            val draft = validExpenseDraft().copy(budgetItemId = DemoData.OPENING_BALANCE)
            rules.validate(draft) shouldContain ValidationError.BudgetItemOutsideTypeSubtree
        }

        @Test
        fun `budget item in correct subtree passes`() {
            rules.validate(validExpenseDraft()) shouldNotContain ValidationError.BudgetItemOutsideTypeSubtree
        }
    }

    @Nested
    inner class AccountRulesMapping {
        @Test
        fun `income - no from, require to`() {
            val ar = rules.accountRules(OperationKind.Income)
            ar shouldBe AccountRules(fromEnabled = false, fromRequired = false, toEnabled = true, toRequired = true)
        }

        @Test
        fun `expense - require from, no to`() {
            val ar = rules.accountRules(OperationKind.Expense)
            ar shouldBe AccountRules(fromEnabled = true, fromRequired = true, toEnabled = false, toRequired = false)
        }

        @Test
        fun `transfer - require both`() {
            val ar = rules.accountRules(OperationKind.Transfer)
            ar shouldBe AccountRules(fromEnabled = true, fromRequired = true, toEnabled = true, toRequired = true)
        }

        @Test
        fun `null kind - both enabled, neither required`() {
            val ar = rules.accountRules(null)
            ar shouldBe AccountRules(fromEnabled = true, fromRequired = false, toEnabled = true, toRequired = false)
        }
    }

    @Nested
    inner class Defaults {
        @Test
        fun `date defaults to today`() {
            rules.defaults(null, today).date shouldBe today
        }

        @Test
        fun `currency defaults to the base currency`() {
            rules.defaults(null, today).currencyId shouldBe DemoData.RUB
        }

        @Test
        fun `falls back to first non-deleted currency when base is ambiguous`() {
            val ds = Dataset(currencies = listOf(Currency(1, "A", null), Currency(2, "B", null)))
            val r = OperationRules(ds)
            r.defaults(null, today).currencyId shouldBe 1L
        }

        @Test
        fun `accountFrom from the most recent operation of the given kind`() {
            val defs = rules.defaults(OperationKind.Expense, today)
            defs.accountFrom shouldBe DemoData.HUSBAND
        }

        @Test
        fun `accountTo from the most recent income operation`() {
            val defs = rules.defaults(OperationKind.Income, today)
            defs.accountTo shouldBe DemoData.HUSBAND
        }
    }
}
