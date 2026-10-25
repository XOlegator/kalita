// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import kalita.core.OperationDraft
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

class OperationWriteToolsTest {
    private fun draft(ids: DemoIds, comment: String = "coffee") = OperationDraft(
        date = LocalDate.of(2026, 1, 5),
        amount = BigDecimal("350.00"),
        currencyId = ids.currencyRub,
        budgetItemId = ids.budgetCafe,
        typeId = ids.typeExpense,
        accountFrom = ids.accountCard,
        comment = comment,
    )

    private fun addOperation(session: AgentSession, ids: DemoIds): Long {
        val result = AddOperationsTool(session).execute(
            drafts = listOf(draft(ids)),
            mode = WriteMode.Write,
            clientRequestId = UUID.randomUUID().toString(),
            device = "agent:test",
        )
        result.getValue("ok").jsonPrimitive.boolean shouldBe true
        return session.dataset.operations.single().id
    }

    @Test
    fun `update_operation replaces fields and is visible on reopen`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session -> addOperation(session, ids) }
        AgentSession.open(file).use { session ->
            val result = UpdateOperationTool(session).execute(
                id = id,
                draft = draft(ids, comment = "taxi"),
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().comment shouldBe "taxi" }
    }

    @Test
    fun `update_operation in draft mode is rejected, nothing changes`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session -> addOperation(session, ids) }
        AgentSession.open(file).use { session ->
            val result = UpdateOperationTool(session).execute(
                id = id,
                draft = draft(ids, comment = "taxi"),
                mode = WriteMode.Draft,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
            session.dataset.operations.single().comment shouldBe "coffee"
        }
    }

    @Test
    fun `update_operation on an unknown id is a clean error`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = UpdateOperationTool(session).execute(
                id = 999_999L,
                draft = draft(ids),
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }

    @Test
    fun `update_operation rejects an invalid draft without writing`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session -> addOperation(session, ids) }
        AgentSession.open(file).use { session ->
            val result = UpdateOperationTool(session).execute(
                id = id,
                draft = draft(ids).copy(amount = null),
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
            result.getValue("error").jsonPrimitive.content shouldBe "validation_failed"
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().comment shouldBe "coffee" }
    }

    @Test
    fun `delete_operation soft-deletes, restore_operation reverses it`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session -> addOperation(session, ids) }

        AgentSession.open(file).use { session ->
            val result = DeleteOperationTool(session).execute(id, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe true }

        AgentSession.open(file).use { session ->
            val result = RestoreOperationTool(session).execute(id, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe false }
    }

    @Test
    fun `delete_operation in read-only mode is rejected`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session -> addOperation(session, ids) }
        AgentSession.open(file).use { session ->
            val result = DeleteOperationTool(session).execute(id, WriteMode.ReadOnly, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
            session.dataset.operations.single().deleted shouldBe false
        }
    }

    @Test
    fun `a replayed client_request_id on delete_operation does not double-apply`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session -> addOperation(session, ids) }
        val requestId = UUID.randomUUID().toString()
        AgentSession.open(file).use { session ->
            val first = DeleteOperationTool(session).execute(id, WriteMode.Write, requestId, "agent:test")
            val second = DeleteOperationTool(session).execute(id, WriteMode.Write, requestId, "agent:test")
            first shouldBe second
        }
    }
}
