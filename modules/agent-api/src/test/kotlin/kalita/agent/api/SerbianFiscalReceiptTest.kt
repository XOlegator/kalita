// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate

class SerbianFiscalReceiptTest {
    private val url = "https://suf.purs.gov.rs/verify?token=synthetic-secret"
    private val response = """{"isValid":true,"invoiceRequest":{"businessName":"Synthetic shop","invoiceType":0,"transactionType":0,
        "items":[{"name":"Item A","totalPrice":10.50},{"name":null,"totalPrice":2.25}]},
        "invoiceResult":{"totalAmount":12.75,"sdcTime":"2026-01-02T12:00:00Z"}}"""

    private fun client(body: String) = SerbianFiscalReceiptClient { body.toByteArray() }

    @Test
    fun `verified sale becomes review input without sensitive fields`() {
        var requested: URI? = null
        val result = SerbianFiscalReceiptClient { requested = it; response.toByteArray() }.verify(url)
        requested.toString() shouldBe url
        result.merchant shouldBe "Synthetic shop"
        result.date shouldBe LocalDate.of(2026, 1, 2)
        result.total shouldBe BigDecimal("12.75")
        result.lines shouldBe listOf(ReceiptLine("Item A", BigDecimal("10.50"), null), ReceiptLine("Позиция чека", BigDecimal("2.25"), null))
        result.currencyId shouldBe null
        result.accountFromId shouldBe null
        result.note shouldBe null
        result.imagePath shouldBe null
    }

    @Test
    fun `untrusted urls are rejected before transport`() {
        val never = SerbianFiscalReceiptClient { error("transport called") }
        listOf("http://suf.purs.gov.rs/", "https://suf.purs.gov.rs.evil.test/", "https://suf.purs.gov.rs@evil.test/",
            "https://user@suf.purs.gov.rs/", "https://suf.purs.gov.rs:444/", "https://suf.purs.gov.rs/#secret",
            "https://127.0.0.1/", "https://suf.purs.gov.rs./", "not a url").forEach {
            shouldThrow<FiscalVerificationException> { never.verify(it) }.message shouldBe "Недопустимый адрес проверки чека"
        }
    }

    @Test
    fun `invalid verification document and amounts fail closed`() {
        val bad = listOf(
            response.replace("\"isValid\":true", "\"isValid\":false"),
            response.replace("\"isValid\":true", "\"isValid\":\"true\""),
            response.replace("\"invoiceType\":0", "\"invoiceType\":1"),
            response.replace("\"transactionType\":0", "\"transactionType\":1"),
            response.replace("12.75", "12.74"), response.replace("10.50", "-10.50"),
            response.replace("10.50", "0"), response.replace("10.50", "\"10.50\""),
            response.replace("\"items\":[", "\"other\":["), "not json $url",
        )
        bad.forEach { body ->
            val error = shouldThrow<FiscalVerificationException> { client(body).verify(url) }
            error.cause shouldBe null
            error.message!!.contains("synthetic-secret") shouldBe false
        }
    }

    @Test
    fun `transport failures and oversized responses do not leak url`() {
        val error = shouldThrow<FiscalVerificationException> {
            SerbianFiscalReceiptClient { throw IllegalStateException(url) }.verify(url)
        }
        error.cause shouldBe null
        error.message!!.contains("synthetic-secret") shouldBe false
        shouldThrow<FiscalVerificationException> { SerbianFiscalReceiptClient { ByteArray(1_048_577) }.verify(url) }
    }

    private class Connection(private val status: Int, private val body: ByteArray, private val length: Long = -1) :
        HttpURLConnection(URI("https://suf.purs.gov.rs/").toURL()) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    @Test
    fun `http layer sets headers and timeouts refuses redirects and caps streamed bodies`() {
        val ok = Connection(200, response.toByteArray())
        readFiscalResponse(ok).toString(Charsets.UTF_8) shouldBe response
        ok.requestMethod shouldBe "GET"
        ok.getRequestProperty("Accept") shouldBe "application/json"
        ok.instanceFollowRedirects shouldBe false
        ok.connectTimeout shouldBe 20_000
        ok.readTimeout shouldBe 30_000
        ok.disconnected shouldBe true
        val redirect = Connection(302, ByteArray(0))
        shouldThrow<FiscalVerificationException> { readFiscalResponse(redirect) }
        redirect.disconnected shouldBe true
        shouldThrow<FiscalVerificationException> { readFiscalResponse(Connection(200, ByteArray(1_048_577))) }
        shouldThrow<FiscalVerificationException> { readFiscalResponse(Connection(200, ByteArray(0), 1_048_577)) }
    }
}