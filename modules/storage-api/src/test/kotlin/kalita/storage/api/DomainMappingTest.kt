// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.api

import io.kotest.matchers.shouldBe
import kalita.core.Account
import kalita.core.BudgetItem
import kalita.core.Counterparty
import kalita.core.Currency
import kalita.core.Operation
import kalita.core.OperationType
import kalita.core.Rate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class DomainMappingTest {
    private val jan26 = LocalDateTime.of(2006, 1, 26, 0, 0)

    @Test
    fun `snapshot rows become domain entities`() {
        val snapshot = DbSnapshot(
            mapOf(
                Table.Operations to listOf(
                    mapOf(
                        "id" to 1L, "del" to false, "Date" to jan26, "Summa" to BigDecimal("100.0000"),
                        "Currency_ID" to 2L, "GroupBudget_ID" to 3L, "TypeOper_ID" to 4L, "Comment" to "c",
                        "People_ID" to 5L, "AccountFrom" to 6L, "AccountTo" to null,
                    ),
                ),
                Table.OperationTypes to listOf(mapOf("id" to 4L, "del" to false, "Name" to "Расход", "GroupBudget_ID" to 7L, "PlusMinus" to -1L)),
                Table.BudgetItems to listOf(mapOf("id" to 3L, "del" to true, "Parent_ID" to null, "Name" to "Расход", "Ord" to 3L)),
                Table.Accounts to listOf(mapOf("id" to 6L, "del" to false, "Name" to "Муж")),
                Table.People to listOf(mapOf("id" to 5L, "del" to null, "Name" to "Обменники")),
                Table.Currencies to listOf(mapOf("id" to 2L, "del" to false, "Name" to "Доллар", "CB_ID" to "R01235")),
                Table.Rates to listOf(mapOf("id" to 9L, "del" to false, "Currency_ID" to 2L, "Date" to jan26, "Course" to 27.9805)),
            ),
        )

        val data = snapshot.toDataset()

        data.operations shouldBe listOf(
            Operation(1, LocalDate.of(2006, 1, 26), BigDecimal("100.0000"), 2, 3, 4, "c", 5, 6, null, false),
        )
        data.operationTypes shouldBe listOf(OperationType(4, "Расход", 7, -1))
        data.budgetItems shouldBe listOf(BudgetItem(3, null, "Расход", 3, deleted = true))
        data.accounts shouldBe listOf(Account(6, "Муж"))
        data.counterparties shouldBe listOf(Counterparty(5, "Обменники"))
        data.currencies shouldBe listOf(Currency(2, "Доллар", "R01235"))
        data.rates shouldBe listOf(Rate(9, 2, LocalDate.of(2006, 1, 26), 27.9805))
    }

    @Test
    fun `missing columns, nulls and other numeric types are read leniently`() {
        val snapshot = DbSnapshot(
            mapOf(
                Table.Operations to listOf(
                    mapOf("id" to 1), // every other column absent
                    mapOf("id" to 2L, "Summa" to 12.5, "Currency_ID" to 3),
                    mapOf("id" to 3L, "Summa" to 7),
                ),
                Table.Rates to listOf(mapOf("id" to 1L, "Course" to 2)),
            ),
        )

        val data = snapshot.toDataset()

        data.operations[0] shouldBe Operation(1, null, null, null, null, null)
        data.operations[1].amount shouldBe BigDecimal("12.5")
        data.operations[1].currencyId shouldBe 3L
        data.operations[2].amount shouldBe BigDecimal(7)
        data.rates.single().value shouldBe 2.0
        DbSnapshot.EMPTY.toDataset().operations shouldBe emptyList()
    }
}
