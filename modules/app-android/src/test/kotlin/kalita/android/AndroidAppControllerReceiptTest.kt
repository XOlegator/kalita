// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.agent.api.RECEIPT_STATUS_REJECTED
import kalita.agent.api.RECEIPT_STATUS_SAVED
import kalita.agent.api.ReceiptImage
import kalita.agent.api.RecognizerConfig
import kalita.agent.api.VisionProvider
import kalita.agent.api.VisionTransport
import kalita.agent.api.ReceiptLine
import kalita.agent.api.ReceiptProposal
import kalita.agent.api.ReceiptProposalStore
import kalita.agent.api.ReceiptReviewModel
import kalita.storage.api.SubmitResult
import kalita.storage.local.defaultDataDirectory
import kalita.storage.mdb.createDemoFixture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The receipt review window's controller half (dev_plan.md §12.19) — plain JVM, like the rest.
class AndroidAppControllerReceiptTest {
    private class MemorySecrets : kalita.platform.SecretStore {
        private val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun open(tempDir: Path): AndroidAppController {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() }, MemorySecrets())
        fixture.inputStream().use { controller.importAndOpen(it) }
        controller.addCurrency("RUB", null)
        controller.addBudgetItem(null, "Еда")
        controller.addBudgetItem(null, "Дом")
        return controller
    }

    /** A proposal file as `kalita receipt propose` leaves it on the agent's machine. */
    private fun proposalFile(tempDir: Path, controller: AndroidAppController): File {
        val ds = (controller.state.value as AndroidAppState.Ready).dataset
        val items = ds.budgetItems.filter { !it.deleted }
        val agentDir = tempDir.resolve("agent").toFile()
        val store = ReceiptProposalStore(agentDir)
        val p = store.add(
            ReceiptProposal(
                id = store.newId(), createdAt = Instant.now(), device = "agent:test", clientRequestId = "r",
                merchant = "Магазин", date = LocalDate.of(2026, 1, 5), currencyId = ds.currencies.first { !it.deleted }.id,
                accountFromId = ds.accounts.first { !it.deleted }.id, total = BigDecimal("300.00"), note = null, imagePath = null,
                lines = listOf(ReceiptLine("Хлеб", BigDecimal("100.00"), items[0].id), ReceiptLine("Сыр", BigDecimal("200.00"), items[1].id)),
                status = "pending", resolution = null,
            ),
        )
        return File(agentDir, "receipt-proposals/${p.id}.json")
    }

    @Test
    fun `an imported receipt is pending, saving writes one change set and records the decision`(@TempDir tempDir: Path) {
        val controller = open(tempDir)
        val file = proposalFile(tempDir, controller)
        val before = (controller.state.value as AndroidAppState.Ready).dataset.operations.size

        val imported = file.inputStream().use { controller.importReceipt(it) }!!
        controller.pendingReceipts() shouldHaveSize 1

        val dataset = (controller.state.value as AndroidAppState.Ready).dataset
        val model = ReceiptReviewModel(imported, dataset).also { it.mergeByItem = false }
        controller.saveReceipt(model).shouldBeInstanceOf<SubmitResult.Applied>()

        (controller.state.value as AndroidAppState.Ready).dataset.operations.size shouldBe before + 2
        controller.pendingReceipts() shouldHaveSize 0
        val dir = defaultDataDirectory(File(tempDir.resolve("private").toFile(), "Data.mdb"))
        ReceiptProposalStore(dir).find(imported.id)!!.status shouldBe RECEIPT_STATUS_SAVED
    }

    @Test
    fun `re-importing a decided receipt does not reopen it, a foreign file is refused`(@TempDir tempDir: Path) {
        val controller = open(tempDir)
        val file = proposalFile(tempDir, controller)
        val imported = file.inputStream().use { controller.importReceipt(it) }!!
        controller.rejectReceipt(imported, " не мой ")

        val again = file.inputStream().use { controller.importReceipt(it) }!!
        again.status shouldBe RECEIPT_STATUS_REJECTED
        controller.pendingReceipts() shouldHaveSize 0
        "не чек".byteInputStream().use { controller.importReceipt(it) } shouldBe null
    }

    @Test
    fun `saving is refused while the receipt does not add up to valid operations`(@TempDir tempDir: Path) {
        val controller = open(tempDir)
        val imported = proposalFile(tempDir, controller).inputStream().use { controller.importReceipt(it) }!!
        val dataset = (controller.state.value as AndroidAppState.Ready).dataset
        val model = ReceiptReviewModel(imported, dataset).also { it.rows[0].budgetItemId = null }

        controller.saveReceipt(model).shouldBeInstanceOf<SubmitResult.Failed>()
        controller.pendingReceipts() shouldHaveSize 1
    }

    @Test
    fun `recognition needs a saved config, then photos become a pending receipt that saves into the journal`(@TempDir tempDir: Path) = runBlocking<Unit> {
        val controller = open(tempDir)
        val ds = (controller.state.value as AndroidAppState.Ready).dataset
        val item = ds.budgetItems.first { !it.deleted }.id
        controller.recognizeReceipt(listOf(ReceiptImage(byteArrayOf(1)))).isFailure shouldBe true

        controller.saveRecognizerConfig(RecognizerConfig(VisionProvider.Anthropic, "https://api.anthropic.com", "m", "KEY"))
        controller.recognizerConfig()!!.apiKey shouldBe "KEY"
        val text = """{"merchant":"Лавка","date":"2026-01-05","total":"150","lines":[{"text":"Хлеб","amount":"150.00","budgetItemId":$item}]}"""
        controller.visionTransport = VisionTransport { _, _, _ ->
            """{"content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(text)}}]}"""
        }

        val proposal = controller.recognizeReceipt(listOf(ReceiptImage(byteArrayOf(1)), ReceiptImage(byteArrayOf(2)))).getOrThrow()
        controller.pendingReceipts().map { it.id } shouldBe listOf(proposal.id)
        controller.receiptFrames(controller.pendingReceipts().single()).map { it.readBytes().toList() } shouldBe listOf(listOf<Byte>(1), listOf<Byte>(2))

        val before = ds.operations.size
        val model = ReceiptReviewModel(proposal, (controller.state.value as AndroidAppState.Ready).dataset)
        model.accountFromId = ds.accounts.first { !it.deleted }.id
        controller.saveReceipt(model).shouldBeInstanceOf<SubmitResult.Applied>()
        (controller.state.value as AndroidAppState.Ready).dataset.operations.size shouldBe before + 1
        controller.pendingReceipts() shouldHaveSize 0
    }

    @Test
    fun `a failing service surfaces as a readable error and leaves nothing pending`(@TempDir tempDir: Path) = runBlocking<Unit> {
        val controller = open(tempDir)
        controller.saveRecognizerConfig(RecognizerConfig(VisionProvider.OpenAiCompatible, "https://x", "m", "K"))
        controller.visionTransport = VisionTransport { _, _, _ -> throw kalita.agent.api.RecognitionException("Нет связи") }
        val e = controller.recognizeReceipt(listOf(ReceiptImage(byteArrayOf(1)))).exceptionOrNull()!!
        e.message shouldBe "Нет связи"
        controller.pendingReceipts() shouldHaveSize 0
    }

    @Test
    fun `cancelling recognition before the transport returns does not queue a receipt`(@TempDir tempDir: Path) = runBlocking<Unit> {
        val controller = open(tempDir)
        controller.saveRecognizerConfig(RecognizerConfig(VisionProvider.OpenAiCompatible, "https://x", "m", "K"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        controller.visionTransport = VisionTransport { _, _, _ ->
            started.countDown()
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    // Model a blocking transport that does not respond to interruption.
                }
            }
            """{"choices":[{"message":{"content":"{\"lines\":[{\"text\":\"Хлеб\",\"amount\":\"1\"}]}"}}]}"""
        }
        val job = launch { controller.recognizeReceipt(listOf(ReceiptImage(byteArrayOf(1)))) }
        try {
            withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) } shouldBe true
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        controller.pendingReceipts() shouldHaveSize 0
    }

    @Test
    fun `a pasted chat answer works without any key, with fences and prose around it`(@TempDir tempDir: Path) = runBlocking<Unit> {
        val controller = open(tempDir)
        val ds = (controller.state.value as AndroidAppState.Ready).dataset
        val item = ds.budgetItems.first { !it.deleted }.id
        val prompt = controller.manualReceiptPrompt(2)!!
        (prompt.contains("budgetItemId") && prompt.contains("эти 2 снимка")) shouldBe true
        controller.recognizerConfig() shouldBe null

        val reply = "Вот результат:\n```json\n{\"merchant\":\"Лавка\",\"date\":\"2026-01-05\",\"lines\":[{\"text\":\"Хлеб\",\"amount\":\"150,00\",\"budgetItemId\":$item}]}\n```"
        val proposal = controller.importRecognizedReply(reply, listOf(ReceiptImage(byteArrayOf(3)), ReceiptImage(byteArrayOf(4)))).getOrThrow()
        proposal.lines.single().amount.compareTo(BigDecimal("150")) shouldBe 0
        controller.pendingReceipts().map { it.id } shouldBe listOf(proposal.id)
        controller.receiptFrames(controller.pendingReceipts().single()).map { it.readBytes().toList() } shouldBe listOf(listOf<Byte>(3), listOf<Byte>(4))

        controller.closeDatabase()
        val reopened = AndroidAppController(tempDir.resolve("private").toFile(), MemorySecrets())
        reopened.openIfExists()
        reopened.receiptFrames(reopened.pendingReceipts().single()).map { it.readBytes().toList() } shouldBe listOf(listOf<Byte>(3), listOf<Byte>(4))

        reopened.importRecognizedReply("не JSON").exceptionOrNull()!!.shouldBeInstanceOf<kalita.agent.api.RecognitionException>()
        reopened.pendingReceipts() shouldHaveSize 1
        reopened.closeDatabase()
    }

    @Test
    fun `foreign paths and unsafe identifiers are never used as frame paths`(@TempDir tempDir: Path) {
        val controller = open(tempDir)
        val file = proposalFile(tempDir, controller)
        val imported = file.inputStream().use { controller.importReceipt(it) }!!
        val secret = tempDir.resolve("secret.jpg").toFile().apply { writeText("secret") }
        controller.receiptFrames(imported.copy(imagePath = secret.absolutePath)) shouldBe emptyList()
        controller.receiptFrames(imported.copy(id = "../secret", imagePath = secret.absolutePath)) shouldBe emptyList()
    }

    @Test
    fun `storage failure reports that proposal is already queued`(@TempDir tempDir: Path) = runBlocking<Unit> {
        val controller = open(tempDir)
        val directory = defaultDataDirectory(File(tempDir.resolve("private").toFile(), "Data.mdb"))
        File(directory, "android-receipt-frames").apply { directory.mkdirs(); writeText("blocked") }
        val reply = """{"lines":[{"text":"Хлеб","amount":"1"}]}"""
        val result = controller.importRecognizedReply(reply, listOf(ReceiptImage(byteArrayOf(1))))
        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message!!.contains("чек добавлен в очередь", ignoreCase = true) shouldBe true
        controller.pendingReceipts() shouldHaveSize 1
    }
}
