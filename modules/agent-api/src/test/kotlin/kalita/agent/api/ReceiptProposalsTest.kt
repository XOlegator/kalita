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
import org.junit.jupiter.api.assertThrows
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
    fun `unfinished review round trips without changing recognition or pending status`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val original = receiptOf(session, input(ids))
            val store = ReceiptProposalStore(session.dataDirectory)
            val model = ReceiptReviewModel(original, session.dataset)
            model.merchant = null
            model.date = null
            model.accountFromId = null
            model.currencyId = null
            model.mergeByItem = false
            model.rows.removeAt(1)
            model.rows[0].apply { text = "edited"; amount = null; budgetItemId = null }
            model.addRow().apply { text = "new"; amount = BigDecimal("1.2300") }
            model.duplicateWarning = "Возможный дубль: операция 42"
            val snapshot = model.reviewDraft()
            store.saveReviewDraft(original.id, snapshot)
            model.rows.clear()

            val loaded = ReceiptProposalStore(session.dataDirectory).find(original.id)!!
            loaded.status shouldBe RECEIPT_STATUS_PENDING
            loaded.resolution shouldBe null
            loaded.lines shouldBe original.lines
            loaded.merchant shouldBe original.merchant
            loaded.reviewDraft shouldBe snapshot
            ReceiptReviewModel(loaded, session.dataset).reviewDraft() shouldBe snapshot
            store.pending().single().id shouldBe original.id
            store.saveReviewDraft(original.id, null).reviewDraft shouldBe null
            ReceiptReviewModel(store.find(original.id)!!, session.dataset).rows.size shouldBe original.lines.size
            store.reject(original, null, session.dataset)
            assertThrows<IllegalArgumentException> { store.saveReviewDraft(original.id, snapshot) }
            assertThrows<IllegalArgumentException> { store.saveReviewDraft("missing", snapshot) }
        }
    }

    @Test
    fun `legacy proposal imports without a review draft`(@TempDir dir: Path) {
        val store = ReceiptProposalStore(dir.toFile())
        val legacy = """{"id":"legacy","createdAt":"2026-01-01T00:00:00Z","device":"agent","clientRequestId":"r","lines":[{"text":"milk","amount":"1.00","budgetItemId":null}],"status":"pending"}"""
        val imported = store.import(legacy)!!
        imported.reviewDraft shouldBe null
        store.find("legacy") shouldBe imported
        store.saveReviewDraft("legacy", ReceiptReviewDraft(null, null, null, null, true, emptyList()))
        store.find("legacy")!!.lines shouldBe imported.lines
    }

    @Test
    fun `possible duplicates are separate nonblocking warnings based on current edits`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val store = ReceiptProposalStore(session.dataDirectory)
            val first = receiptOf(session, input(ids))
            val second = receiptOf(session, input(ids, "1000.0").copy(merchant = "  пЯТЁРОЧКА  "))
            val different = receiptOf(session, input(ids, "999"))
            val model = ReceiptReviewModel(first, session.dataset)
            model.possibleDuplicates(store).map { it.id } shouldBe listOf(second.id)
            model.problems().shouldBeEmpty()
            model.merchant = null
            model.possibleDuplicates(store).shouldBeEmpty()
            model.merchant = "Пятёрочка"
            model.date = null
            model.possibleDuplicates(store).shouldBeEmpty()
            store.reject(second, null, session.dataset)
            store.possibleDuplicates(first).shouldBeEmpty()
            store.possibleDuplicates(different).shouldBeEmpty()
        }
    }

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

    @Test
    fun `serbian scripts match learned items while original exact matches take priority`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val store = ReceiptLearningStore(session.dataDirectory)
            store.append(kotlinx.serialization.json.Json.parseToJsonElement(
                """{"status":"saved","merchant":"Пекара","lines":[{"text":"МЛЕКО ЉУБИЧИЦА","finalItemId":${ids.budgetFood}},{"text":"Mleko Ljubičica","finalItemId":${ids.budgetCafe}},{"text":"ХЛЕБ БЕЛИ","finalItemId":${ids.budgetFood}}]}""",
            ).jsonObject)
            val cyrillic = store.hints(listOf("млеко љубичица"), "Pekara", session.dataset).single()
            cyrillic.budgetItemId shouldBe ids.budgetFood
            cyrillic.basis shouldBe "exact"
            val latin = store.hints(listOf("MLEKO LJUBIC\u030cICA"), "Пекара", session.dataset).single()
            latin.budgetItemId shouldBe ids.budgetCafe
            latin.basis shouldBe "exact"
            val crossExact = store.hints(listOf("hleb beli"), "Pekara", session.dataset).single()
            crossExact.budgetItemId shouldBe ids.budgetFood
            crossExact.basis shouldBe "exact"
            val crossScript = store.hints(listOf("млеко љубичица велико"), null, session.dataset).single()
            crossScript.basis shouldBe "similar"
            store.hints(listOf("сир"), null, session.dataset).single().basis shouldBe "none"
        }
    }

    @Test
    fun `serbian alphabet transliteration covers distinct letters and digraphs`() {
        ReceiptLearningStore.normalizeSerbian("ЂЋЏЉЊЖШЧЈ") shouldBe "đćdžljnjžščj"
        ReceiptLearningStore.normalizeSerbian("Mleko LJUBIC\u030cICA 500g") shouldBe "mleko ljubičica"
    }

    private fun receiptOf(session: AgentSession, input: ReceiptProposalInput): ReceiptProposal {
        val id = ProposeReceiptTool(session).execute(input, WriteMode.Draft, "req-${System.nanoTime()}", "agent:test")["proposalId"]!!.jsonPrimitive.content
        return ReceiptProposalStore(session.dataDirectory).find(id)!!
    }
}
