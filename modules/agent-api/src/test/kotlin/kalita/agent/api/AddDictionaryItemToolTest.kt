// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

class AddDictionaryItemToolTest {
    @Test
    fun `adds a new account visible on reopen`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        val id = AgentSession.open(file).use { session ->
            val result = AddDictionaryItemTool(session).execute(
                kind = DictionaryKind.Account,
                name = "Вклад",
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            result.getValue("id").jsonPrimitive.long
        }
        AgentSession.open(file).use { session -> session.dataset.account(id)?.name shouldBe "Вклад" }
    }

    @Test
    fun `adds a budget item under an existing parent`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session ->
            val result = AddDictionaryItemTool(session).execute(
                kind = DictionaryKind.BudgetItem,
                name = "Кино",
                parentId = ids.budgetFood,
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            result.getValue("id").jsonPrimitive.long
        }
        AgentSession.open(file).use { session -> session.dataset.budgetItem(id)?.parentId shouldBe ids.budgetFood }
    }

    @Test
    fun `a budget item with an unknown parent is a clean error`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = AddDictionaryItemTool(session).execute(
                kind = DictionaryKind.BudgetItem,
                name = "Кино",
                parentId = 999_999L,
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
        AgentSession.open(file).use { session -> session.dataset.budgetItems.none { it.name == "Кино" } shouldBe true }
    }

    @Test
    fun `a blank name is a clean error`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = AddDictionaryItemTool(session).execute(
                kind = DictionaryKind.Account,
                name = "  ",
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }

    @Test
    fun `a currency gets its code from the name and its first rate in the same change`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        val day = LocalDate.of(2026, 11, 14)
        AgentSession.open(file).use { session ->
            val tool = AddDictionaryItemTool(session)
            fun add(name: String, cbId: String? = null, rate: CurrencyRate? = null, kind: DictionaryKind = DictionaryKind.Currency) =
                tool.execute(kind, name, null, cbId, WriteMode.Write, UUID.randomUUID().toString(), "agent:test", rate)

            val dinar = add("Сербский динар", rate = CurrencyRate(day, 0.772483))
            dinar.getValue("cbId").jsonPrimitive.content shouldBe "RSD"
            dinar.getValue("rate").jsonObject.getValue("value").jsonPrimitive.content shouldBe "0.772483"
            dinar["rateHint"] shouldBe null

            val tenge = add("Тенге", cbId = "R01335")
            tenge.getValue("cbId").jsonPrimitive.content shouldBe "R01335"
            tenge.getValue("rateHint").jsonPrimitive.content shouldContain "No exchange rate"

            add("Крона", rate = CurrencyRate(day, 0.0)).getValue("ok").jsonPrimitive.boolean shouldBe false
            add("Вклад", rate = CurrencyRate(day, 1.0), kind = DictionaryKind.Account).getValue("ok").jsonPrimitive.boolean shouldBe false
        }
        AgentSession.open(file).use { session ->
            val dinar = session.dataset.currencies.single { it.name == "Сербский динар" }
            dinar.cbId shouldBe "RSD"
            session.dataset.rates.single { it.currencyId == dinar.id }.let {
                it.date shouldBe day
                it.value shouldBe 0.772483
            }
            session.dataset.currencies.none { it.name == "Крона" } shouldBe true
        }
    }

    @Test
    fun `draft mode is rejected — no draft fallback for dictionary writes`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = AddDictionaryItemTool(session).execute(
                kind = DictionaryKind.Currency,
                name = "Тенге",
                cbId = "R01335",
                mode = WriteMode.Draft,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
        AgentSession.open(file).use { session -> session.dataset.currencies.none { it.name == "Тенге" } shouldBe true }
    }

    @Test
    fun `a replayed client_request_id does not create a duplicate`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        val requestId = UUID.randomUUID().toString()
        AgentSession.open(file).use { session ->
            val first = AddDictionaryItemTool(session).execute(
                kind = DictionaryKind.Counterparty,
                name = "Мария",
                mode = WriteMode.Write,
                clientRequestId = requestId,
                device = "agent:test",
            )
            val second = AddDictionaryItemTool(session).execute(
                kind = DictionaryKind.Counterparty,
                name = "Мария",
                mode = WriteMode.Write,
                clientRequestId = requestId,
                device = "agent:test",
            )
            first shouldBe second
            session.dataset.counterparties.count { it.name == "Мария" } shouldBe 1
        }
    }
}
