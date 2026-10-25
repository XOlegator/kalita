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

/** [UndoChangesTool] (dev_plan.md §12.13-§12.14's `undo_changes`): reverses one change set found in
 * `ChangeHistory`, scoped to the device that created it. */
class UndoChangesToolTest {
    private fun draft(ids: DemoIds, comment: String = "coffee") = OperationDraft(
        date = LocalDate.of(2026, 1, 5),
        amount = BigDecimal("350.00"),
        currencyId = ids.currencyRub,
        budgetItemId = ids.budgetCafe,
        typeId = ids.typeExpense,
        accountFrom = ids.accountCard,
        comment = comment,
    )

    private fun changeSetIdOf(result: kotlinx.serialization.json.JsonObject): String = result.getValue("changeSetId").jsonPrimitive.content

    @Test
    fun `undoes an inserted operation by soft-deleting it`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val changeSetId = AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            changeSetIdOf(result)
        }

        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(changeSetId, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            result.getValue("undoneChangeSetId").jsonPrimitive.content shouldBe changeSetId
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe true }
    }

    @Test
    fun `undoes an update by restoring the original fields`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)), mode = WriteMode.Write, clientRequestId = UUID.randomUUID().toString(), device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            session.dataset.operations.single().id
        }

        val updateChangeSetId = AgentSession.open(file).use { session ->
            val result = UpdateOperationTool(session).execute(
                id = id, draft = draft(ids, comment = "taxi"), mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(), device = "agent:test",
            )
            changeSetIdOf(result)
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().comment shouldBe "taxi" }

        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(updateChangeSetId, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().comment shouldBe "coffee" }
    }

    @Test
    fun `undoes a soft-delete by restoring the operation`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)), mode = WriteMode.Write, clientRequestId = UUID.randomUUID().toString(), device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            session.dataset.operations.single().id
        }

        val deleteChangeSetId = AgentSession.open(file).use { session ->
            val result = DeleteOperationTool(session).execute(id, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            changeSetIdOf(result)
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe true }

        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(deleteChangeSetId, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe false }
    }

    @Test
    fun `undoes a new dictionary item by soft-deleting it`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        val (changeSetId, accountId) = AgentSession.open(file).use { session ->
            val result = AddDictionaryItemTool(session).execute(
                kind = DictionaryKind.Account, name = "Вклад", mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(), device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            changeSetIdOf(result) to result.getValue("id").jsonPrimitive.content.toLong()
        }

        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(changeSetId, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
        }
        AgentSession.open(file).use { session ->
            session.dataset.accounts.single { it.id == accountId }.deleted shouldBe true
        }
    }

    @Test
    fun `cannot undo a change set created by a different device`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val changeSetId = AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)), mode = WriteMode.Write, clientRequestId = UUID.randomUUID().toString(), device = "agent:one",
            )
            changeSetIdOf(result)
        }

        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(changeSetId, WriteMode.Write, UUID.randomUUID().toString(), "agent:two")
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe false }
    }

    @Test
    fun `unknown changeSetId is a clean error`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(UUID.randomUUID().toString(), WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }

    @Test
    fun `a malformed changeSetId is a clean error, not a crash`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute("not-a-uuid", WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }

    @Test
    fun `undo_changes in draft mode is rejected`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val changeSetId = AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)), mode = WriteMode.Write, clientRequestId = UUID.randomUUID().toString(), device = "agent:test",
            )
            changeSetIdOf(result)
        }

        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(changeSetId, WriteMode.Draft, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe false }
    }

    @Test
    fun `a replayed client_request_id does not double-apply`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val changeSetId = AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)), mode = WriteMode.Write, clientRequestId = UUID.randomUUID().toString(), device = "agent:test",
            )
            changeSetIdOf(result)
        }

        val requestId = UUID.randomUUID().toString()
        AgentSession.open(file).use { session ->
            val first = UndoChangesTool(session).execute(changeSetId, WriteMode.Write, requestId, "agent:test")
            val second = UndoChangesTool(session).execute(changeSetId, WriteMode.Write, requestId, "agent:test")
            first shouldBe second
        }
    }

    @Test
    fun `undoing an undo restores the operation again`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val changeSetId = AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)), mode = WriteMode.Write, clientRequestId = UUID.randomUUID().toString(), device = "agent:test",
            )
            changeSetIdOf(result)
        }

        val undoChangeSetId = AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(changeSetId, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            changeSetIdOf(result)
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe true }

        AgentSession.open(file).use { session ->
            val result = UndoChangesTool(session).execute(undoChangeSetId, WriteMode.Write, UUID.randomUUID().toString(), "agent:test")
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
        }
        AgentSession.open(file).use { session -> session.dataset.operations.single().deleted shouldBe false }
    }
}
