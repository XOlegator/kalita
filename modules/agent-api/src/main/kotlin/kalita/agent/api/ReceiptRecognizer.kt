// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kalita.core.OperationKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.Base64

/** Which wire format the vision model speaks. */
enum class VisionProvider(val label: String, val defaultBaseUrl: String, val defaultModel: String) {
    /** Anthropic Messages API (Claude). */
    Anthropic("Claude (Anthropic)", "https://api.anthropic.com", "claude-sonnet-5-5"),

    /** OpenAI-style `/chat/completions` with image parts — OpenAI, OpenRouter, Together, a self-hosted vLLM/Ollama, … */
    OpenAiCompatible("OpenAI-совместимый API", "https://api.openai.com/v1", "gpt-4o"),
}

data class RecognizerConfig(val provider: VisionProvider, val baseUrl: String, val model: String, val apiKey: String) {
    val ready: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
}

/** One photographed part of a receipt, already scaled down for upload. */
class ReceiptImage(val bytes: ByteArray, val mimeType: String = "image/jpeg")

/** Recognition failed in a way worth showing to the person as text (network, key, unreadable photo, bad answer). */
class RecognitionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One blocking HTTPS POST; a seam so the recogniser is testable without a network. Throws [RecognitionException]. */
fun interface VisionTransport {
    fun post(url: String, headers: Map<String, String>, body: String): String
}

object HttpVisionTransport : VisionTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): String {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw RecognitionException("Сервис распознавания ответил ошибкой $code: ${errorText(text)}")
            return text
        } catch (e: RecognitionException) {
            throw e
        } catch (e: IOException) {
            throw RecognitionException("Нет связи с сервисом распознавания: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorText(body: String): String = runCatching {
        val o = Json.parseToJsonElement(body).jsonObject
        (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: o["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(300)
}

/**
 * Receipt photos → a pending [ReceiptProposal] (dev_plan.md §12.19, §12.1): the phone has no agent of its
 * own and the chat apps (Claude, DeepSeek) offer no way to hand them pictures and take a structured
 * answer back, so Kalita asks a vision model's API directly. What it gets back is validated against the
 * open database (unknown ids dropped), corrected by what earlier receipts taught ([ReceiptLearningStore]),
 * and stored as an ordinary proposal — the same review window and the same «nothing is written until the
 * person confirms» rule as for an agent on the computer. [publish] receives the fully prepared proposal
 * and controls its storage; by default it is written immediately, as in the original contract.
 */
class ReceiptRecognizer(
    private val transport: VisionTransport = HttpVisionTransport,
    private val publish: (ReceiptProposalStore, ReceiptProposal) -> ReceiptProposal = { store, proposal -> store.add(proposal) },
) {

    fun recognize(
        images: List<ReceiptImage>,
        dataset: Dataset,
        dataDirectory: java.io.File,
        config: RecognizerConfig,
        device: String,
        now: Instant = Instant.now(),
    ): ReceiptProposal {
        if (images.isEmpty()) throw RecognitionException("Нет снимков чека")
        if (!config.ready) throw RecognitionException("Распознавание не настроено: нужны адрес, модель и ключ")
        val request = buildRequest(config, images, dataset)
        val reply = transport.post(request.url, request.headers, request.body)
        return store(parseReply(config.provider, reply, dataset), dataset, dataDirectory, device, now)
    }

    /**
     * The no-key path: the person ran [ReceiptPrompt.manual] in a chat app they already pay for and pasted
     * the model's answer. Same validation and learning as [recognize]; the answer is only text, so the
     * same «nothing is written before the review» rule holds.
     */
    fun importReply(
        text: String,
        dataset: Dataset,
        dataDirectory: java.io.File,
        device: String,
        now: Instant = Instant.now(),
    ): ReceiptProposal = store(parseModelJson(text, dataset), dataset, dataDirectory, device, now)

    private fun store(input: ReceiptProposalInput, dataset: Dataset, dataDirectory: java.io.File, device: String, now: Instant): ReceiptProposal {
        val store = ReceiptProposalStore(dataDirectory)
        val lines = applyLearning(input, dataset, dataDirectory)
        return publish(store,
            ReceiptProposal(
                id = store.newId(), createdAt = now, device = device, clientRequestId = "recognize-${store.newId()}",
                merchant = input.merchant?.trim()?.ifEmpty { null }, date = input.date, currencyId = input.currencyId,
                accountFromId = input.accountFromId, total = input.total, note = input.note, imagePath = null,
                lines = lines, status = RECEIPT_STATUS_PENDING, resolution = null,
            ),
        )
    }

    class Request(val url: String, val headers: Map<String, String>, val body: String)

    internal fun buildRequest(config: RecognizerConfig, images: List<ReceiptImage>, dataset: Dataset): Request {
        val system = ReceiptPrompt.system(dataset)
        val task = ReceiptPrompt.task(images.size)
        val base = config.baseUrl.trimEnd('/')
        return when (config.provider) {
            VisionProvider.Anthropic -> Request(
                url = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages",
                headers = mapOf("x-api-key" to config.apiKey, "anthropic-version" to "2023-06-01"),
                body = buildJsonObject {
                    put("model", config.model)
                    put("max_tokens", 8192)
                    put("system", system)
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put(
                                        "content",
                                        buildJsonArray {
                                            images.forEachIndexed { i, img ->
                                                add(buildJsonObject { put("type", "text"); put("text", "Снимок ${i + 1} из ${images.size}:") })
                                                add(
                                                    buildJsonObject {
                                                        put("type", "image")
                                                        put(
                                                            "source",
                                                            buildJsonObject {
                                                                put("type", "base64"); put("media_type", img.mimeType)
                                                                put("data", Base64.getEncoder().encodeToString(img.bytes))
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                            add(buildJsonObject { put("type", "text"); put("text", task) })
                                        },
                                    )
                                },
                            )
                        },
                    )
                }.toString(),
            )
            VisionProvider.OpenAiCompatible -> Request(
                url = "$base/chat/completions",
                headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
                body = buildJsonObject {
                    put("model", config.model)
                    put("temperature", 0)
                    put(
                        "messages",
                        buildJsonArray {
                            add(buildJsonObject { put("role", "system"); put("content", system) })
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put(
                                        "content",
                                        buildJsonArray {
                                            images.forEachIndexed { i, img ->
                                                add(buildJsonObject { put("type", "text"); put("text", "Снимок ${i + 1} из ${images.size}:") })
                                                add(
                                                    buildJsonObject {
                                                        put("type", "image_url")
                                                        put("image_url", buildJsonObject { put("url", "data:${img.mimeType};base64," + Base64.getEncoder().encodeToString(img.bytes)) })
                                                    },
                                                )
                                            }
                                            add(buildJsonObject { put("type", "text"); put("text", task) })
                                        },
                                    )
                                },
                            )
                        },
                    )
                }.toString(),
            )
        }
    }

    /** The model's text out of a provider response, then the validated proposal input out of that text. */
    internal fun parseReply(provider: VisionProvider, responseBody: String, dataset: Dataset): ReceiptProposalInput {
        val text = try {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            when (provider) {
                VisionProvider.Anthropic -> root.getValue("content").jsonArray
                    .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    .joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
                VisionProvider.OpenAiCompatible -> root.getValue("choices").jsonArray.first().jsonObject
                    .getValue("message").jsonObject.getValue("content").let { c ->
                        if (c is JsonArray) c.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() } else c.jsonPrimitive.content
                    }
            }
        } catch (e: Exception) {
            throw RecognitionException("Неожиданный ответ сервиса распознавания", e)
        }
        return parseModelJson(text, dataset)
    }

    internal fun parseModelJson(text: String, dataset: Dataset): ReceiptProposalInput {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw RecognitionException("Модель не вернула данные чека: ${text.take(200)}")
        val o = try {
            Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            throw RecognitionException("Ответ модели не разобрать как данные чека", e)
        }
        o["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { throw RecognitionException("Чек не распознан: $it") }
        val lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { el ->
            val l = el as? JsonObject ?: return@mapNotNull null
            val label = l["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val amount = l["amount"]?.jsonPrimitive?.contentOrNull?.let(::decimal)?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            if (label.isEmpty()) return@mapNotNull null
            val item = l["budgetItemId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { dataset.budgetItem(it)?.deleted == false }
            ReceiptLine(label, amount, item)
        }
        if (lines.isEmpty()) throw RecognitionException("На снимках не найдено ни одной позиции чека")
        fun id(name: String) = o[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return ReceiptProposalInput(
            merchant = o["merchant"]?.jsonPrimitive?.contentOrNull,
            date = o["date"]?.jsonPrimitive?.contentOrNull?.let { runCatching { java.time.LocalDate.parse(it.trim().take(10)) }.getOrNull() },
            currencyId = id("currencyId")?.takeIf { dataset.currency(it)?.deleted == false },
            accountFromId = id("accountFromId")?.takeIf { dataset.account(it)?.deleted == false },
            total = o["total"]?.jsonPrimitive?.contentOrNull?.let(::decimal),
            note = o["note"]?.jsonPrimitive?.contentOrNull,
            imagePath = null,
            lines = lines,
        )
    }

    private fun decimal(s: String): BigDecimal? = s.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()

    /** What earlier receipts taught beats a fresh guess when it is confident (same rule as `get_receipt_hints`). */
    private fun applyLearning(input: ReceiptProposalInput, dataset: Dataset, dataDirectory: java.io.File): List<ReceiptLine> {
        val hints = runCatching { ReceiptLearningStore(dataDirectory).hints(input.lines.map { it.text }, input.merchant, dataset) }.getOrDefault(emptyList())
        val byText = hints.filter { it.confidence >= HINT_CONFIDENCE }.associateBy { it.text }
        return input.lines.map { l -> byText[l.text]?.let { l.copy(budgetItemId = it.budgetItemId) } ?: l }
    }

    private companion object {
        const val HINT_CONFIDENCE = 0.7
    }
}

/** The instructions the vision model gets — the whole contract between Kalita and the model lives here. */
object ReceiptPrompt {
    fun system(dataset: Dataset): String = buildString {
        appendLine("Ты разбираешь фотографии кассовых чеков для программы учёта личных финансов Kalita.")
        appendLine("Верни ТОЛЬКО один JSON-объект, без пояснений и без markdown:")
        appendLine("""{"merchant": "название магазина или null", "date": "YYYY-MM-DD или null", "currencyId": число или null, "accountFromId": число или null, "total": "итог чека, например 1234.50, или null", "note": "короткая заметка или null", "lines": [{"text": "название позиции как на чеке", "amount": "сумма по позиции, например 199.99", "budgetItemId": число или null}]}""")
        appendLine("Если чек прочитать нельзя, верни {\"error\": \"причина по-русски\"}.")
        appendLine()
        appendLine("Правила:")
        appendLine("- Все снимки — части ОДНОГО чека, по порядку сверху вниз. Участки могут перекрываться: не дублируй позиции из перекрытия.")
        appendLine("- По одной строке `lines` на каждую покупку. `amount` — итоговая стоимость позиции (количество × цена), положительное число с точкой. Скидку по позиции вычти из её суммы; общую скидку на весь чек не выдумывай.")
        appendLine("- Не включай в `lines` налоги (ПДВ/НДС), способ оплаты, сдачу, бонусы, итоговые строки. `total` — сумма к оплате, напечатанная на чеке.")
        appendLine("- `text` оставляй на языке чека (можно сократить мусор), `date` — дата чека.")
        appendLine("- `budgetItemId` выбирай ТОЛЬКО из списка статей ниже, самую подходящую (лист, а не группу, если есть); не уверен — null. `currencyId` и `accountFromId` — только из списков ниже, иначе null.")
        appendLine()
        appendLine("Статьи расходов (id — путь):")
        for (item in expenseItems(dataset)) appendLine("${item.first} — ${item.second}")
        appendLine()
        appendLine("Валюты (id — название):")
        for (c in dataset.currencies.filter { !it.deleted }) appendLine("${c.id} — ${c.name}")
        appendLine()
        appendLine("Счета (id — название):")
        for (a in dataset.accounts.filter { !it.deleted }) appendLine("${a.id} — ${a.name}")
    }

    /** One self-contained message for a chat app: the contract, then the task (the photos are attached by hand). */
    fun manual(dataset: Dataset, imageCount: Int): String = system(dataset) + "\n" + task(imageCount)

    fun task(imageCount: Int): String =
        "Разбери ${if (imageCount == 1) "этот снимок чека" else "эти $imageCount снимка как части одного чека"} и верни JSON по описанному формату."

    private fun expenseItems(dataset: Dataset): List<Pair<Long, String>> {
        val rootId = dataset.operationTypes.firstOrNull { !it.deleted && it.kind == OperationKind.Expense }?.budgetRootId
        fun inBranch(id: Long): Boolean {
            if (rootId == null) return true
            var cur = dataset.budgetItem(id)
            val seen = HashSet<Long>()
            while (cur != null && seen.add(cur.id)) {
                if (cur.id == rootId) return true
                cur = dataset.budgetItem(cur.parentId)
            }
            return false
        }
        return dataset.budgetItems.filter { !it.deleted && inBranch(it.id) && it.id != rootId }
            .mapNotNull { item -> budgetPath(dataset, item.id)?.let { item.id to it } }
            .sortedBy { it.second }
    }
}
