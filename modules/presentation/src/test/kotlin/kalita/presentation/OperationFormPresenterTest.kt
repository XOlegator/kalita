// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.core.ValidationError
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OperationFormPresenterTest {
    private val presenter = OperationFormPresenter().also { it.load(DemoPresentationData.dataset) }

    @Test
    fun `new operation gets default date and currency`() {
        presenter.newOperation()
        val draft = presenter.state.value.draft
        draft.date shouldBe java.time.LocalDate.now()
        draft.currencyId shouldBe DemoPresentationData.RUB
    }

    @Test
    fun `new expense gets default accountFrom`() {
        presenter.newOperation(OperationKind.Expense)
        presenter.state.value.draft.accountFrom shouldBe DemoPresentationData.HUSBAND
    }

    @Test
    fun `expense account rules disable accountTo`() {
        presenter.newOperation(OperationKind.Expense)
        val ar = presenter.state.value.accountRules
        ar.fromEnabled shouldBe true
        ar.fromRequired shouldBe true
        ar.toEnabled shouldBe false
        ar.toRequired shouldBe false
    }

    @Test
    fun `valid expense draft has no errors`() {
        presenter.editOperation(
            OperationDraft(
                date = java.time.LocalDate.now(),
                amount = BigDecimal("100"),
                currencyId = DemoPresentationData.RUB,
                budgetItemId = DemoPresentationData.EXCHANGE_OUT,
                typeId = DemoPresentationData.EXPENSE,
                accountFrom = DemoPresentationData.MAIN,
            ),
        )
        presenter.state.value.errors.shouldBeEmpty()
    }

    @Test
    fun `missing amount is reported`() {
        presenter.editOperation(OperationDraft(date = java.time.LocalDate.now()))
        presenter.state.value.errors shouldContain ValidationError.MissingAmount
    }

    @Test
    fun `updateField marks the form dirty`() {
        presenter.newOperation()
        presenter.state.value.dirty shouldBe false
        presenter.updateField { copy(comment = "test") }
        presenter.state.value.dirty shouldBe true
    }

    @Test
    fun `clear resets the form`() {
        presenter.newOperation(OperationKind.Income)
        presenter.clear()
        presenter.state.value.draft shouldBe OperationDraft()
        presenter.state.value.dirty shouldBe false
    }
}
