// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate

class GetContextToolTest {
    @Test
    fun `reports today, base currency and live dictionaries`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val json = GetContextTool(session.dataset).execute(today = LocalDate.of(2026, 10, 24))

            json.jsonObject.getValue("today").jsonPrimitive.content shouldBe "2026-10-24"
            json.jsonObject.getValue("baseCurrency").jsonObject.getValue("id").jsonPrimitive.content shouldBe ids.currencyRub.toString()
            json.jsonObject.getValue("accounts").jsonArray shouldHaveSize 2
            json.jsonObject.getValue("currencies").jsonArray shouldHaveSize 1
            json.jsonObject.getValue("counterparties").jsonArray shouldHaveSize 1
            json.jsonObject.getValue("budgetItems").jsonArray shouldHaveSize 3
            json.jsonObject.getValue("operationTypes").jsonArray shouldHaveSize 3
        }
    }
}
