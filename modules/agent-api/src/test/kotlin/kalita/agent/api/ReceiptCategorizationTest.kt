// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.AtomicCommit
import kalita.storage.mdb.CommitResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ReceiptCategorizationTest {
    @Test
    fun `weak learning does not block dictionary in agent tools`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val cs = ChangeSet(UUID.randomUUID(), "fixture", null, Instant.EPOCH, "expense root", listOf(
            Op.Insert(Table.OperationTypes, TempKey("rooted"), mapOf("Name" to "Расходы", "PlusMinus" to -1L, "GroupBudget_ID" to ids.budgetFood, "del" to false)),
        ))
        (AtomicCommit.commit(file, listOf(cs)) is CommitResult.Committed) shouldBe true
        AgentSession.open(file).use { session ->
            ReceiptCategorizationStore(session.dataDirectory).replaceDictionary(listOf(ReceiptDictionaryEntry("товар", ids.budgetFood)))
            val learning = ReceiptLearningStore(session.dataDirectory)
            listOf(ids.budgetCafe, ids.budgetTransport).forEach { id ->
                learning.append(Json.parseToJsonElement("""{"status":"saved","lines":[{"text":"товар","finalItemId":$id}]}""").jsonObject)
            }
            learning.hints(listOf("товар"), null, session.dataset).single().confidence shouldBe 0.5
            val hint = GetReceiptHintsTool(session).execute(listOf("товар"), null)["hints"]!!.jsonArray.single().jsonObject
            hint["basis"]!!.jsonPrimitive.content shouldBe "dictionary"
            hint["budgetItemId"]!!.jsonPrimitive.content.toLong() shouldBe ids.budgetFood
            val input = ReceiptProposalInput(null, null, null, null, null, null, null, listOf(ReceiptLine("товар", BigDecimal.ONE, null)))
            val result = ProposeReceiptTool(session).execute(input, WriteMode.Draft, "r", "d")
            ReceiptProposalStore(session.dataDirectory).find(result["proposalId"]!!.jsonPrimitive.content)!!.lines.single().budgetItemId shouldBe ids.budgetFood
        }
    }
    @Test
    fun `rules and dictionary preserve digits and single letter words`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val cs = ChangeSet(UUID.randomUUID(), "fixture", null, Instant.EPOCH, "expense root", listOf(
            Op.Insert(Table.OperationTypes, TempKey("rooted"), mapOf("Name" to "Расходы", "PlusMinus" to -1L, "GroupBudget_ID" to ids.budgetFood, "del" to false)),
        ))
        (AtomicCommit.commit(file, listOf(cs)) is CommitResult.Committed) shouldBe true
        AgentSession.open(file).use { session ->
            val store = ReceiptCategorizationStore(session.dataDirectory)
            store.replaceRules(listOf(ReceiptCategoryRule("Магазин 1 А", "товар 2 б", ids.budgetFood)))
            store.rule("ТОВАР 2 Б", "МАГАЗИН 1 А", session.dataset) shouldBe ids.budgetFood
            store.rule("ТОВАР 2 Б", "МАГАЗИН 2 А", session.dataset) shouldBe null
            store.rule("ТОВАР 3 Б", "МАГАЗИН 1 А", session.dataset) shouldBe null
            store.rule("ТОВАР 2 В", "МАГАЗИН 1 А", session.dataset) shouldBe null
            store.replaceDictionary(listOf(ReceiptDictionaryEntry("2 б", ids.budgetFood)))
            store.dictionaryHint("товар 2 б", session.dataset) shouldBe ids.budgetFood
            store.dictionaryHint("товар 3 б", session.dataset) shouldBe null
            store.dictionaryHint("товар 2 в", session.dataset) shouldBe null
        }
    }
    @Test
    fun `rules and dictionary apply across agent paths and preserve explicit selections`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val cs = ChangeSet(UUID.randomUUID(), "fixture", null, Instant.EPOCH, "expense root", listOf(
            Op.Insert(Table.OperationTypes, TempKey("rooted"), mapOf("Name" to "Расходы", "PlusMinus" to -1L, "GroupBudget_ID" to ids.budgetFood, "del" to false)),
        ))
        (AtomicCommit.commit(file, listOf(cs)) is CommitResult.Committed) shouldBe true
        AgentSession.open(file).use { session ->
            val settings = ReceiptCategorizationStore(session.dataDirectory)
            val rule = ReceiptCategoryRule("Пекара", "млеко", ids.budgetCafe)
            val entry = ReceiptDictionaryEntry("хлеб", ids.budgetFood)
            settings.replaceRules(listOf(rule))
            settings.replaceDictionary(listOf(entry))
            ReceiptCategorizationStore(session.dataDirectory).rules() shouldBe listOf(rule)
            settings.dictionary() shouldBe listOf(entry)
            ReceiptLearningStore(session.dataDirectory).append(Json.parseToJsonElement(
                """{"status":"saved","lines":[{"text":"MLEKO","finalItemId":${ids.budgetFood}}]}""",
            ).jsonObject)
            val hints = GetReceiptHintsTool(session).execute(listOf("MLEKO", "HLEB"), "Pekara")["hints"]!!.jsonArray
            hints.map { it.jsonObject["basis"]!!.jsonPrimitive.content } shouldBe listOf("rule", "dictionary")
            val input = ReceiptProposalInput("Pekara", null, null, null, null, null, null, listOf(
                ReceiptLine("MLEKO", BigDecimal.ONE, null), ReceiptLine("MLEKO", BigDecimal.ONE, ids.budgetTransport),
                ReceiptLine("HLEB", BigDecimal.ONE, null),
            ))
            val result = ProposeReceiptTool(session).execute(input, WriteMode.Draft, "r", "d")
            val proposal = ReceiptProposalStore(session.dataDirectory).find(result["proposalId"]!!.jsonPrimitive.content)!!
            proposal.lines.map { it.budgetItemId } shouldBe listOf(ids.budgetCafe, ids.budgetTransport, ids.budgetFood)
            val model = ReceiptRecognizer().importInput(input, session.dataset, session.dataDirectory, "d")
            model.lines.first().budgetItemId shouldBe ids.budgetCafe
            settings.replaceRules(listOf(rule.copy(budgetItemId = 999999)))
            categorizationHints(listOf("MLEKO"), "Pekara", session.dataset, session.dataDirectory).single().budgetItemId shouldBe ids.budgetFood
        }
    }
}