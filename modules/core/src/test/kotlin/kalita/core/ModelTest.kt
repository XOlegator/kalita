// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ModelTest {
    @Test
    fun `operation kind and sign come from PlusMinus only`() {
        OperationType(99, "Доход", null, 1).kind shouldBe OperationKind.Income
        OperationType(98, "Расход", null, -1).kind shouldBe OperationKind.Expense
        OperationType(97, "Перемещение", null, 0).kind shouldBe OperationKind.Transfer
        OperationType(96, null, null, null).kind shouldBe null

        OperationType(1, null, null, -1).sign shouldBe -1
        OperationType(1, null, null, 0).sign shouldBe 1
        OperationType(1, null, null, null).sign shouldBe 1
        OperationType(1, null, null, 5).sign shouldBe 1
        OperationType(1, null, null, -5).sign shouldBe -1
    }

    @Test
    fun `lookups by id tolerate null and unknown ids`() {
        val data = DemoData.dataset
        data.operationType(DemoData.EXPENSE)?.name shouldBe "Расход"
        data.operationType(null) shouldBe null
        data.currency(DemoData.USD)?.cbId shouldBe "R01235"
        data.currency(-1) shouldBe null
        data.account(DemoData.BANK)?.name shouldBe "Счет в банке"
        data.account(null) shouldBe null
    }
}
