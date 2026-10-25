// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
