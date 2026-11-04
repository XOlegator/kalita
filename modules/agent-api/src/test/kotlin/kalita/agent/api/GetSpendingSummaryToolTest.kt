// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kalita.storage.api.Table
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

class GetSpendingSummaryToolTest {
    @Test
    fun `groups income and expense by budget item, excluding transfers`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            fun insert(date: LocalDate, amount: String, budgetItem: Long, type: Long, from: Long? = null, to: Long? = null) {
                session.submit("op", "test") { b ->
                    b.insert(
                        Table.Operations, b.nextTempKey(),
                        mapOf(
                            "Date" to date.atStartOfDay(), "Summa" to BigDecimal(amount), "Currency_ID" to ids.currencyRub,
                            "GroupBudget_ID" to budgetItem, "TypeOper_ID" to type, "AccountFrom" to from, "AccountTo" to to,
                        ),
                    )
                }
            }
            insert(LocalDate.of(2026, 1, 1), "1000", ids.budgetFood, ids.typeIncome, to = ids.accountCard)
            insert(LocalDate.of(2026, 1, 2), "350", ids.budgetCafe, ids.typeExpense, from = ids.accountCard)
            insert(LocalDate.of(2026, 1, 3), "200", ids.budgetCafe, ids.typeExpense, from = ids.accountCard)
            // A transfer between the user's own accounts — excluded entirely (dev_plan.md §12.8).
            insert(LocalDate.of(2026, 1, 4), "500", ids.budgetTransport, ids.typeTransfer, from = ids.accountCard, to = ids.accountCash)

            val result = GetSpendingSummaryTool(session.dataset).execute(dateFrom = LocalDate.of(2026, 1, 1), dateTo = LocalDate.of(2026, 1, 31))

            result.getValue("byBudgetItem").jsonArray.map { line ->
                val o = line.jsonObject
                listOf(o.getValue("budgetItem").jsonPrimitive.content, o.getValue("income").jsonPrimitive.content, o.getValue("expense").jsonPrimitive.content, o.getValue("count").jsonPrimitive.content)
            } shouldContainExactlyInAnyOrder listOf(
                // A budget item with no operations of the other kind reports a plain zero (no
                // operations to take a scale from), unlike a real summed amount's "350.0000".
                listOf("Еда", "1000.0000", "0", "1"),
                listOf("Кафе", "0", "-550.0000", "2"),
            )
            val totals = result.getValue("totals").jsonObject
            totals.getValue("income").jsonPrimitive.content shouldBe "1000.0000"
            totals.getValue("expense").jsonPrimitive.content shouldBe "-550.0000"
            result.getValue("transferCount").jsonPrimitive.content shouldBe "1"
            result.getValue("currenciesWithoutRate").jsonArray.size shouldBe 0
        }
    }

    @Test
    fun `defaults to the current month when no range is given`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            session.submit("op", "test") { b ->
                b.insert(
                    Table.Operations, b.nextTempKey(),
                    mapOf(
                        "Date" to LocalDate.now().atStartOfDay(), "Summa" to BigDecimal("42"), "Currency_ID" to ids.currencyRub,
                        "GroupBudget_ID" to ids.budgetFood, "TypeOper_ID" to ids.typeIncome, "AccountTo" to ids.accountCard,
                    ),
                )
            }

            val result = GetSpendingSummaryTool(session.dataset).execute()
            result.getValue("byBudgetItem").jsonArray.single().jsonObject.getValue("income").jsonPrimitive.content shouldBe "42.0000"
        }
    }
}
