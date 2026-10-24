// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.collections.shouldBeEmpty
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

class InboxToolsTest {
    private fun draft(ids: DemoIds) = OperationDraft(
        date = LocalDate.of(2026, 1, 5),
        amount = BigDecimal("350.00"),
        currencyId = ids.currencyRub,
        budgetItemId = ids.budgetCafe,
        typeId = ids.typeExpense,
        accountFrom = ids.accountCard,
        comment = "coffee",
    )

    @Test
    fun `approve commits the inbox entry and removes it from the pending list`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val added = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Draft,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            val inboxId = added.getValue("inbox").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content

            val approved = ApproveInboxTool(session).execute(inboxId)
            approved.getValue("ok").jsonPrimitive.boolean shouldBe true

            ListInboxTool(session).execute().getValue("entries").jsonArray.shouldBeEmpty()
            session.dataset.operations.size shouldBe 1
        }
    }

    @Test
    fun `reject marks the entry rejected without touching the mdb`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val added = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Draft,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            val inboxId = added.getValue("inbox").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content

            val rejected = RejectInboxTool(session).execute(inboxId)
            rejected.getValue("ok").jsonPrimitive.boolean shouldBe true

            ListInboxTool(session).execute().getValue("entries").jsonArray.shouldBeEmpty()
            session.dataset.operations.size shouldBe 0

            // A second reject of the same (no longer pending) entry is an error, not a crash.
            RejectInboxTool(session).execute(inboxId).getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }

    @Test
    fun `approving an unknown id is a clean error`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            ApproveInboxTool(session).execute("no-such-id").getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }
}
