// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import kalita.core.OperationDraft
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

class AddOperationsToolTest {
    private fun draft(ids: DemoIds, amount: String = "350.00", date: LocalDate = LocalDate.of(2026, 1, 5)) = OperationDraft(
        date = date,
        amount = BigDecimal(amount),
        currencyId = ids.currencyRub,
        budgetItemId = ids.budgetCafe,
        typeId = ids.typeExpense,
        accountFrom = ids.accountCard,
        comment = "coffee",
    )

    @Test
    fun `dry-run previews without writing anything`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Write,
                dryRun = true,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            result.getValue("dryRun").jsonPrimitive.boolean shouldBe true
            val accepted = result.getValue("accepted").jsonArray
            accepted.size shouldBe 1
            accepted[0].jsonObject.getValue("preview").jsonObject.getValue("budgetItem").jsonPrimitive.content shouldBe "Кафе"
        }
        AgentSession.open(file).use { session -> session.dataset.operations.size shouldBe 0 }
    }

    @Test
    fun `draft mode writes to the inbox, not the mdb`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Draft,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            result.getValue("inbox").jsonArray.size shouldBe 1
            session.dataset.operations.size shouldBe 0
            ListInboxTool(session).execute().getValue("entries").jsonArray.size shouldBe 1
        }
    }

    @Test
    fun `write mode commits and is visible on reopen`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe true
            result.getValue("appliedCount").jsonPrimitive.content shouldBe "1"
        }
        AgentSession.open(file).use { session ->
            session.dataset.operations.size shouldBe 1
            session.dataset.operations[0].comment shouldBe "coffee"
        }
    }

    @Test
    fun `a draft over the per-operation limit is rejected, others still apply`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids, amount = "100.00"), draft(ids, amount = "9000.00")),
                mode = WriteMode.Write,
                limits = WriteLimits(maxOperationAmount = BigDecimal("1000.00")),
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("appliedCount").jsonPrimitive.content shouldBe "1"
            result.getValue("rejected").jsonArray.size shouldBe 1
        }
        AgentSession.open(file).use { session -> session.dataset.operations.size shouldBe 1 }
    }

    @Test
    fun `a replayed client_request_id returns the same result without duplicating`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val requestId = UUID.randomUUID().toString()
        AgentSession.open(file).use { session ->
            val first = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Write,
                clientRequestId = requestId,
                device = "agent:test",
            )
            val second = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Write,
                clientRequestId = requestId,
                device = "agent:test",
            )
            first shouldBe second
        }
        AgentSession.open(file).use { session -> session.dataset.operations.size shouldBe 1 }
    }

    @Test
    fun `an invalid draft fails the whole batch, nothing is written`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val invalid = draft(ids).copy(amount = null)
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids), invalid),
                mode = WriteMode.Write,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
            result.getValue("error").jsonPrimitive.content shouldBe "validation_failed"
        }
        AgentSession.open(file).use { session -> session.dataset.operations.size shouldBe 0 }
    }

    @Test
    fun `read-only mode is rejected without writing anything`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.ReadOnly,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            result.getValue("ok").jsonPrimitive.boolean shouldBe false
        }
        AgentSession.open(file).use { session -> session.dataset.operations.size shouldBe 0 }
    }
}
