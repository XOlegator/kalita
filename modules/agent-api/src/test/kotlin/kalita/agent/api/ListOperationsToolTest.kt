// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import kalita.storage.api.Table
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

class ListOperationsToolTest {
    private fun addOperation(
        session: AgentSession,
        date: LocalDate,
        amount: BigDecimal,
        currencyId: Long,
        budgetItemId: Long,
        typeId: Long,
        accountFrom: Long? = null,
        accountTo: Long? = null,
        comment: String? = null,
    ) {
        session.submit("test op", "test") { builder ->
            builder.insert(
                Table.Operations,
                builder.nextTempKey(),
                mapOf(
                    "Date" to date.atStartOfDay(),
                    "Summa" to amount,
                    "Currency_ID" to currencyId,
                    "GroupBudget_ID" to budgetItemId,
                    "TypeOper_ID" to typeId,
                    "AccountFrom" to accountFrom,
                    "AccountTo" to accountTo,
                    "Comment" to comment,
                ),
            )
        }
    }

    @Test
    fun `filters by date range, budget item and text, and paginates`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            addOperation(session, LocalDate.of(2026, 1, 5), BigDecimal("350.00"), ids.currencyRub, ids.budgetCafe, ids.typeExpense, accountFrom = ids.accountCard, comment = "coffee")
            addOperation(session, LocalDate.of(2026, 1, 6), BigDecimal("600.00"), ids.currencyRub, ids.budgetTransport, ids.typeExpense, accountFrom = ids.accountCash, comment = "taxi")
            addOperation(session, LocalDate.of(2026, 2, 1), BigDecimal("1000.00"), ids.currencyRub, ids.budgetCafe, ids.typeExpense, accountFrom = ids.accountCard)

            val tool = ListOperationsTool(session.dataset)
            val byDate = tool.execute(dateFrom = LocalDate.of(2026, 1, 1), dateTo = LocalDate.of(2026, 1, 31))
            byDate.getValue("totalCount").jsonPrimitive.int shouldBe 2

            val byCategory = tool.execute(budgetItemIds = setOf(ids.budgetFood))
            byCategory.getValue("totalCount").jsonPrimitive.int shouldBe 2 // withDescendants: Еда includes Кафе

            val byText = tool.execute(commentSubstring = "taxi")
            byText.getValue("totalCount").jsonPrimitive.int shouldBe 1

            val paged = tool.execute(offset = 1, limit = 1)
            paged.getValue("rows").jsonArray.size shouldBe 1
            paged.getValue("totalCount").jsonPrimitive.int shouldBe 3
        }
    }

    @Test
    fun `reports an aggregate sum over the filtered rows`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            addOperation(session, LocalDate.of(2026, 1, 1), BigDecimal("100.00"), ids.currencyRub, ids.budgetCafe, ids.typeExpense, accountFrom = ids.accountCard)
            addOperation(session, LocalDate.of(2026, 1, 2), BigDecimal("50.00"), ids.currencyRub, ids.budgetCafe, ids.typeExpense, accountFrom = ids.accountCard)

            val result = ListOperationsTool(session.dataset).execute()
            result.getValue("aggregate").jsonObject.getValue("sumAmount").jsonPrimitive.content shouldBe "-150.0000"
        }
    }
}
