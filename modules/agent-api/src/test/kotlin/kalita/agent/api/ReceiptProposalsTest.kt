// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

class ReceiptProposalsTest {
    private fun input(ids: DemoIds, total: String? = "1000.00") = ReceiptProposalInput(
        merchant = "Пятёрочка", date = LocalDate.of(2026, 1, 5), currencyId = ids.currencyRub, accountFromId = ids.accountCard,
        total = total?.let(::BigDecimal), note = null, imagePath = null,
        lines = listOf(
            ReceiptLine("Молоко 3.2% 1л", BigDecimal("100.00"), ids.budgetFood),
            ReceiptLine("Кофе капучино", BigDecimal("250.00"), ids.budgetFood),
            ReceiptLine("Пакет", BigDecimal("650.00"), ids.budgetTransport),
        ),
    )

    @Test
    fun `propose stores a pending proposal, is idempotent and refuses read-only`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val tool = ProposeReceiptTool(session)
            val first = tool.execute(input(ids), WriteMode.Draft, "req-1", "agent:test")
            first["ok"]!!.jsonPrimitive.boolean shouldBe true
            first["linesMatchReceiptTotal"]!!.jsonPrimitive.boolean shouldBe true
            val again = tool.execute(input(ids), WriteMode.Draft, "req-1", "agent:test")
            again["proposalId"] shouldBe first["proposalId"]
            ReceiptProposalStore(session.dataDirectory).pending() shouldHaveSize 1
            tool.execute(input(ids), WriteMode.ReadOnly, "req-2", "agent:test")["ok"]!!.jsonPrimitive.boolean shouldBe false
        }
    }

    @Test
    fun `propose rejects unknown items and empty lines`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val bad = input(ids).copy(lines = listOf(ReceiptLine("x", BigDecimal("1"), 99999)))
            ProposeReceiptTool(session).execute(bad, WriteMode.Draft, "r", "d")["error"]!!.jsonPrimitive.content shouldBe "validation_failed"
            ProposeReceiptTool(session).execute(input(ids).copy(lines = emptyList()), WriteMode.Draft, "r2", "d")["ok"]!!.jsonPrimitive.boolean shouldBe false
            ReceiptProposalStore(session.dataDirectory).pending().shouldBeEmpty()
        }
    }

    @Test
    fun `review model sums lines, groups by item and blocks an incomplete receipt`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val proposal = receiptOf(session, input(ids, total = "1000.00"))
            val model = ReceiptReviewModel(proposal, session.dataset)

            model.linesTotal() shouldBe BigDecimal("1000.00")
            model.difference()!!.compareTo(BigDecimal.ZERO) shouldBe 0
            model.problems().shouldBeEmpty()
            val merged = model.drafts()
            merged shouldHaveSize 2
            merged.first { it.budgetItemId == ids.budgetFood }.amount shouldBe BigDecimal("350.00")
            merged.first().comment!!.startsWith("Пятёрочка: ") shouldBe true
            merged.all { it.typeId == ids.typeExpense && it.accountFrom == ids.accountCard } shouldBe true

            model.mergeByItem = false
            model.drafts() shouldHaveSize 3

            model.rows[0].amount = BigDecimal("90.00")
            model.difference()!!.compareTo(BigDecimal("10.00")) shouldBe 0
            model.accountFromId = null
            model.rows[1].budgetItemId = null
            model.problems() shouldHaveSize 2
        }
    }

    @Test
    fun `saving teaches the layer and hints reflect the corrections`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val store = ReceiptProposalStore(session.dataDirectory)
            val proposal = receiptOf(session, input(ids))
            val model = ReceiptReviewModel(proposal, session.dataset)
            model.rows[1].budgetItemId = ids.budgetCafe // corrected: coffee is Кафе, not Еда
            model.changedByPerson(model.rows[1]) shouldBe true
            model.rows.removeAt(2) // the bag was removed
            model.addRow().apply { text = "Хлеб"; amount = BigDecimal("40.00"); budgetItemId = ids.budgetFood }

            val saved = store.complete(proposal, model.resolution("cs-1", 2), session.dataset)
            ReceiptFeedback.outcomes(saved).map { it.kind } shouldBe
                listOf(LineOutcomeKind.Confirmed, LineOutcomeKind.Corrected, LineOutcomeKind.Added, LineOutcomeKind.Removed)

            val hints = ReceiptLearningStore(session.dataDirectory).hints(listOf("Кофе капучино 0,3л", "Хлеб", "Пакет", "Неизвестное"), "Пятёрочка", session.dataset)
            hints[0].budgetItemId shouldBe ids.budgetCafe
            hints[0].basis shouldBe "exact"
            hints[0].oftenCorrectedFrom shouldBe "Еда"
            hints[1].budgetItemId shouldBe ids.budgetFood
            hints[2].basis shouldBe "none" // removed lines teach nothing
            hints[3].budgetItemId shouldBe null

            val result = GetReceiptProposalTool(session).execute(proposal.id)
            result["status"]!!.jsonPrimitive.content shouldBe RECEIPT_STATUS_SAVED
            result["lines"]!!.jsonArray.map { it.jsonObject["outcome"]!!.jsonPrimitive.content } shouldBe listOf("confirmed", "corrected", "added", "removed")
        }
    }

    @Test
    fun `a rejected receipt is reported to the agent and teaches nothing`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val proposal = receiptOf(session, input(ids))
            ReceiptProposalStore(session.dataDirectory).reject(proposal, "не мой чек", session.dataset)

            val result = GetReceiptProposalTool(session).execute(proposal.id)
            result["status"]!!.jsonPrimitive.content shouldBe RECEIPT_STATUS_REJECTED
            result["reason"]!!.jsonPrimitive.content shouldBe "не мой чек"
            ReceiptLearningStore(session.dataDirectory).hints(listOf("Молоко"), null, session.dataset)[0].basis shouldBe "none"
        }
    }

    @Test
    fun `similar texts match by shared words`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val proposal = receiptOf(session, input(ids))
            val model = ReceiptReviewModel(proposal, session.dataset)
            ReceiptProposalStore(session.dataDirectory).complete(proposal, model.resolution(null, 3), session.dataset)

            val hint = ReceiptLearningStore(session.dataDirectory).hints(listOf("Кофе капучино большой"), null, session.dataset).single()
            hint.basis shouldBe "similar"
            hint.budgetItemId shouldBe ids.budgetFood
        }
    }

    private fun receiptOf(session: AgentSession, input: ReceiptProposalInput): ReceiptProposal {
        val id = ProposeReceiptTool(session).execute(input, WriteMode.Draft, "req-${System.nanoTime()}", "agent:test")["proposalId"]!!.jsonPrimitive.content
        return ReceiptProposalStore(session.dataDirectory).find(id)!!
    }
}
