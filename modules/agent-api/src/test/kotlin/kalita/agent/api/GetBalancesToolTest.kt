// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

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

class GetBalancesToolTest {
    @Test
    fun `computes received, spent and balance per account, and the ИТОГО total`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            // Income of 1000 to Card, expense of 350 from Card.
            session.submit("income", "test") { b ->
                b.insert(
                    Table.Operations, b.nextTempKey(),
                    mapOf(
                        "Date" to LocalDate.of(2026, 1, 1).atStartOfDay(), "Summa" to BigDecimal("1000.00"),
                        "Currency_ID" to ids.currencyRub, "GroupBudget_ID" to ids.budgetFood, "TypeOper_ID" to ids.typeIncome,
                        "AccountTo" to ids.accountCard,
                    ),
                )
            }
            session.submit("expense", "test") { b ->
                b.insert(
                    Table.Operations, b.nextTempKey(),
                    mapOf(
                        "Date" to LocalDate.of(2026, 1, 2).atStartOfDay(), "Summa" to BigDecimal("350.00"),
                        "Currency_ID" to ids.currencyRub, "GroupBudget_ID" to ids.budgetCafe, "TypeOper_ID" to ids.typeExpense,
                        "AccountFrom" to ids.accountCard,
                    ),
                )
            }

            val result = GetBalancesTool(session.dataset).execute()
            val cardLine = result.getValue("lines").jsonArray.first {
                it.jsonObject.getValue("account").jsonPrimitive.content == "Карта"
            }.jsonObject
            cardLine.getValue("received").jsonPrimitive.content shouldBe "1000.0000"
            cardLine.getValue("spent").jsonPrimitive.content shouldBe "350.0000"
            cardLine.getValue("balance").jsonPrimitive.content shouldBe "650.0000"

            val total = result.getValue("total").jsonObject
            total.getValue("balance").jsonPrimitive.content shouldBe "650.0000"
            total.getValue("currenciesWithoutRate").jsonArray.size shouldBe 0
        }
    }
}
