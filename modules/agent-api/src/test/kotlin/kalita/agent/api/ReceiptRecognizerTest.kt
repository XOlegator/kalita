// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

class ReceiptRecognizerTest {
    @Test
    fun `dictionary fills missing items without overriding model or learned choices`(@TempDir dir: Path) {
        val data = kalita.core.Dataset(
            operationTypes = listOf(kalita.core.OperationType(1, "Расход", 1, -1)),
            budgetItems = listOf(
                kalita.core.BudgetItem(1, null, "Расход", null),
                kalita.core.BudgetItem(2, 1, "Молоко", null),
                kalita.core.BudgetItem(3, 1, "Другое", null),
            ),
        )
        val recognizer = ReceiptRecognizer()
        fun import(lines: String) = recognizer.importReply("""{"lines":[$lines]}""", data, dir.toFile(), "test")
        val first = import("""{"text":"MLEKO","amount":"10"},{"text":"МЛЕКО","amount":"10","budgetItemId":3},{"text":"unknown","amount":"10"}""")
        first.lines.map { it.budgetItemId } shouldBe listOf(2L, 3L, null)
        ReceiptLearningStore(dir.toFile()).append(Json.parseToJsonElement(
            """{"status":"saved","lines":[{"text":"MLEKO","finalItemId":3}]}""",
        ).jsonObject)
        import("""{"text":"MLEKO","amount":"10"}""").lines.single().budgetItemId shouldBe 3L
    }

    @Test
    fun `structured receipt input uses groceries fallback`(@TempDir dir: Path) {
        val data = kalita.core.Dataset(
            operationTypes = listOf(kalita.core.OperationType(1, "Расход", 1, -1)),
            budgetItems = listOf(kalita.core.BudgetItem(1, null, "Расход", null), kalita.core.BudgetItem(2, 1, "Продукты", null)),
        )
        val input = SerbianFiscalReceiptClient { """{"isValid":true,"invoiceRequest":{"invoiceType":0,"transactionType":0,"items":[{"name":"MLEKO","totalPrice":10}]},"invoiceResult":{"totalAmount":10}}""".toByteArray() }
            .verify("https://suf.purs.gov.rs/v/?vl=synthetic")
        val proposal = ReceiptRecognizer().importInput(input, data, dir.toFile(), "test")
        proposal.lines.single().budgetItemId shouldBe 2L
    }

    @Test
    fun `publishing a new proposal never replaces an existing proposal`(@TempDir dir: Path) {
        val store = ReceiptProposalStore(dir.toFile())
        val proposal = ReceiptRecognizer().importReply(
            """{"lines":[{"text":"original","amount":"1"}]}""", kalita.core.Dataset.EMPTY, dir.toFile(), "android",
        )
        shouldThrow<java.nio.file.FileAlreadyExistsException> {
            store.addNew(proposal.copy(merchant = "replacement"))
        }
        store.find(proposal.id) shouldBe proposal
    }

    private class Fake(val reply: String) : VisionTransport {
        var url = ""; var headers = emptyMap<String, String>(); var body = ""
        override fun post(url: String, headers: Map<String, String>, body: String): String {
            this.url = url; this.headers = headers; this.body = body
            return reply
        }
    }

    private fun modelJson(ids: DemoIds) =
        """{"merchant":"Maxi","date":"2026-01-05","currencyId":${ids.currencyRub},"accountFromId":${ids.accountCard},"total":"350,00","note":null,
           "lines":[{"text":"Молоко","amount":"100.00","budgetItemId":${ids.budgetFood}},
                    {"text":"Кофе","amount":"250,00","budgetItemId":99999},
                    {"text":"Мусор","amount":"0","budgetItemId":null}]}"""

    private fun anthropicReply(text: String) = """{"content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}]}"""

    @Test
    fun `anthropic request carries every image, the catalog and the key, reply becomes a pending proposal`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val fake = Fake(anthropicReply("Вот результат:\n```json\n${modelJson(ids)}\n```"))
        AgentSession.open(file).use { session ->
            val config = RecognizerConfig(VisionProvider.Anthropic, "https://api.anthropic.com", "m", "KEY")
            val images = listOf(ReceiptImage(byteArrayOf(1, 2, 3)), ReceiptImage(byteArrayOf(4, 5)))
            val p = ReceiptRecognizer(fake).recognize(images, session.dataset, session.dataDirectory, config, "android")

            fake.url shouldBe "https://api.anthropic.com/v1/messages"
            fake.headers["x-api-key"] shouldBe "KEY"
            val body = Json.parseToJsonElement(fake.body).jsonObject
            body["system"]!!.jsonPrimitive.content shouldContain "Статьи расходов"
            val parts = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            parts.count { it.jsonObject["type"]!!.jsonPrimitive.content == "image" } shouldBe 2

            p.status shouldBe RECEIPT_STATUS_PENDING
            p.merchant shouldBe "Maxi"
            p.date shouldBe LocalDate.of(2026, 1, 5)
            p.total shouldBe BigDecimal("350.00")
            p.accountFromId shouldBe ids.accountCard
            p.lines shouldHaveSize 2                       // the zero-amount line is dropped
            p.lines[0].budgetItemId shouldBe ids.budgetFood
            p.lines[1].budgetItemId shouldBe null          // unknown id is not trusted
            p.lines[1].amount shouldBe BigDecimal("250.00")
            ReceiptProposalStore(session.dataDirectory).pending() shouldHaveSize 1
        }
    }

    @Test
    fun `openai-compatible request uses data urls and bearer auth`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val reply = """{"choices":[{"message":{"content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), modelJson(ids))}}}]}"""
        val fake = Fake(reply)
        AgentSession.open(file).use { session ->
            val config = RecognizerConfig(VisionProvider.OpenAiCompatible, "https://x.example/v1/", "gpt", "K2")
            ReceiptRecognizer(fake).recognize(listOf(ReceiptImage(byteArrayOf(9))), session.dataset, session.dataDirectory, config, "android")
            fake.url shouldBe "https://x.example/v1/chat/completions"
            fake.headers["Authorization"] shouldBe "Bearer K2"
            fake.body shouldContain "data:image/jpeg;base64,"
        }
    }

    @Test
    fun `confident hints from earlier receipts override the model's budget item`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val learned = ReceiptProposalStore(session.dataDirectory).add(
                ReceiptProposal(
                    "old", java.time.Instant.now(), "d", "c", "Maxi", null, null, null, null, null, null,
                    listOf(ReceiptLine("Молоко", BigDecimal("100"), ids.budgetFood)), RECEIPT_STATUS_PENDING, null,
                ),
            )
            val resolution = ReceiptResolution(
                java.time.Instant.now(), "Maxi", null, null, null,
                listOf(ReceiptFinalLine("Молоко", BigDecimal("100"), ids.budgetTransport, 0)), 1, "cs", null,
            )
            ReceiptProposalStore(session.dataDirectory).complete(learned, resolution, session.dataset)
            repeat(3) {
                val again = ReceiptProposalStore(session.dataDirectory).add(learned.copy(id = "o$it", clientRequestId = "c$it"))
                ReceiptProposalStore(session.dataDirectory).complete(again, resolution, session.dataset)
            }
            val fake = Fake(anthropicReply(modelJson(ids)))
            val config = RecognizerConfig(VisionProvider.Anthropic, "https://api.anthropic.com/v1", "m", "K")
            val p = ReceiptRecognizer(fake).recognize(listOf(ReceiptImage(byteArrayOf(1))), session.dataset, session.dataDirectory, config, "android")
            fake.url shouldBe "https://api.anthropic.com/v1/messages"
            p.lines[0].budgetItemId shouldBe ids.budgetTransport
        }
    }

    @Test
    fun `refusals and unusable answers are reported as readable errors`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val config = RecognizerConfig(VisionProvider.Anthropic, "https://a", "m", "K")
            val img = listOf(ReceiptImage(byteArrayOf(1)))
            fun run(reply: String, cfg: RecognizerConfig = config, images: List<ReceiptImage> = img) =
                ReceiptRecognizer(Fake(reply)).recognize(images, session.dataset, session.dataDirectory, cfg, "android")

            shouldThrow<RecognitionException> { run(anthropicReply("""{"error":"размыто"}""")) }.message!! shouldContain "размыто"
            shouldThrow<RecognitionException> { run(anthropicReply("простите, не могу")) }
            shouldThrow<RecognitionException> { run(anthropicReply("""{"lines":[]}""")) }.message!! shouldContain "ни одной позиции"
            shouldThrow<RecognitionException> { run("not json") }
            shouldThrow<RecognitionException> { run("{}", config.copy(apiKey = "")) }.message!! shouldContain "не настроено"
            shouldThrow<RecognitionException> { run("{}", images = emptyList()) }
            ReceiptProposalStore(session.dataDirectory).pending() shouldHaveSize 0
        }
    }
}
