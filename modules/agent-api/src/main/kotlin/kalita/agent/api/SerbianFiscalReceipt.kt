// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.time.OffsetDateTime
import javax.net.ssl.HttpsURLConnection

/** Failures deliberately contain neither the verification URL nor the server response. */
class FiscalVerificationException(message: String) : Exception(message)

/** Blocking transport seam. Implementations must not follow redirects or disclose the URL in errors. */
fun interface FiscalVerificationTransport {
    fun get(url: URI): ByteArray
}

private const val MAX_RESPONSE_BYTES = 1_048_576

/** Default transport: HTTPS GET, no redirects, bounded response and finite timeouts. */
object HttpFiscalVerificationTransport : FiscalVerificationTransport {
    override fun get(url: URI): ByteArray {
        validateFiscalUrl(url)
        try {
            val connection = url.toURL().openConnection() as HttpsURLConnection
            return readFiscalResponse(connection)
        } catch (e: FiscalVerificationException) {
            throw e
        } catch (_: Exception) {
            throw FiscalVerificationException("Не удалось связаться с сервисом проверки чека")
        }
    }
}

internal fun readFiscalResponse(connection: HttpURLConnection): ByteArray {
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        if (connection.responseCode != 200) throw FiscalVerificationException("Сервис проверки вернул ошибку HTTP")
        if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw FiscalVerificationException("Ответ сервиса слишком велик")
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) throw FiscalVerificationException("Ответ сервиса слишком велик")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

private fun validateFiscalUrl(uri: URI) {
    if (!uri.isAbsolute || uri.isOpaque || !uri.scheme.equals("https", true) ||
        !uri.host.equals("suf.purs.gov.rs", true) || uri.rawUserInfo != null ||
        (uri.port != -1 && uri.port != 443) || uri.rawFragment != null || uri.rawAuthority?.endsWith(":") == true
    ) throw FiscalVerificationException("Недопустимый адрес проверки чека")
}

/**
 * Fetch a verified Serbian normal sale as review input. No proposal or operation is persisted.
 * Call off the UI thread; the caller chooses account, currency and categories during review.
 * Only the production Tax Administration host is accepted. The URL is never stored in the result.
 */
class SerbianFiscalReceiptClient(private val transport: FiscalVerificationTransport = HttpFiscalVerificationTransport) {
    fun verify(url: String): ReceiptProposalInput {
        val uri = try {
            if (url.length > 8192) throw IllegalArgumentException()
            URI(url).also(::validateFiscalUrl)
        } catch (_: Exception) {
            throw FiscalVerificationException("Недопустимый адрес проверки чека")
        }
        val bytes = try {
            transport.get(uri)
        } catch (_: Exception) {
            throw FiscalVerificationException("Не удалось получить ответ сервиса проверки чека")
        }
        if (bytes.size > MAX_RESPONSE_BYTES) throw FiscalVerificationException("Ответ сервиса слишком велик")
        return try {
            parseFiscalReceipt(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw FiscalVerificationException("Ответ проверки чека недействителен или не поддерживается")
        }
    }
}

private fun parseFiscalReceipt(text: String): ReceiptProposalInput {
    val root = Json.parseToJsonElement(text) as JsonObject
    require((root["isValid"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true)
    val request = root["invoiceRequest"] as JsonObject
    val result = root["invoiceResult"] as JsonObject
    fun zero(name: String): Boolean = (request[name] as? JsonPrimitive)?.let { !it.isString && it.content == "0" } == true
    require(zero("invoiceType") && zero("transactionType"))
    fun amount(value: Any?): BigDecimal {
        val p = value as JsonPrimitive
        require(!p.isString && p.content.length <= 32 && p.content.matches(Regex("[0-9]+(\\.[0-9]+)?")))
        return BigDecimal(p.content).also { require(it.signum() > 0) }
    }
    val total = amount(result["totalAmount"])
    val items = request["items"] as JsonArray
    require(items.isNotEmpty() && items.size <= 10_000)
    val lines = items.map {
        val item = it as JsonObject
        val name = (item["name"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()
        ReceiptLine(name?.takeIf(String::isNotEmpty) ?: "Позиция чека", amount(item["totalPrice"]), null)
    }
    require(lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.amount }.compareTo(total) == 0)
    val date = (result["sdcTime"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { OffsetDateTime.parse(it).toLocalDate() }
    val merchant = (request["businessName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    return ReceiptProposalInput(merchant, date, null, null, total, null, null, lines)
}