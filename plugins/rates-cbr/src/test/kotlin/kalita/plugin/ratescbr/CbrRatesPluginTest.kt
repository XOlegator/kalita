// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.ratescbr

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kalita.core.Currency
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginHost
import kalita.plugin.api.PluginHttpClient
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import kalita.plugin.api.RateSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.ServiceLoader

class CbrRatesPluginTest {
    private val plugin = CbrRatesPlugin()

    private val daily = """<?xml version="1.0" encoding="windows-1251"?><ValCurs Date="14.11.2026" name="Foreign Currency Market">
        <Valute ID="R01235"><NumCode>840</NumCode><CharCode>USD</CharCode><Nominal>1</Nominal><Name>x</Name><Value>80,1234</Value><VunitRate>80,1234</VunitRate></Valute>
        <Valute ID="R01805F"><NumCode>941</NumCode><CharCode>RSD</CharCode><Nominal>100</Nominal><Name>y</Name><Value>77,2483</Value><VunitRate>0,772483</VunitRate></Valute>
        </ValCurs>"""

    private fun dynamic(id: String, vararg records: Triple<String, Int, String>) =
        """<?xml version="1.0" encoding="windows-1251"?><ValCurs ID="$id" DateRange1="01.11.2026" DateRange2="15.11.2026" name="Foreign Currency Market Dynamic">""" +
            records.joinToString("") { (date, nominal, value) -> """<Record Date="$date" Id="$id"><Nominal>$nominal</Nominal><Value>$value</Value><VunitRate>0</VunitRate></Record>""" } +
            "</ValCurs>"

    private class Host(private val responses: (String) -> String) : PluginHost {
        val requests = mutableListOf<String>()
        override val httpClient = object : PluginHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): String {
                requests += url
                return responses(url)
            }

            override suspend fun post(url: String, body: String, headers: Map<String, String>): String = error("unused")
        }
        override val secretStore = object : PluginSecretStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = Unit
            override fun remove(key: String) = Unit
        }
        override val logger = PluginLogger { _, _ -> }
        override val clock: Clock = Clock.systemUTC()
    }

    private val ruble = Currency(12, "Рубль", null)
    private val dollar = Currency(10, "Доллар", "R01235")
    private val dinar = Currency(20, "RSD", null)
    private val hryvnia = Currency(17, "Гривна", "UAH")

    @Test
    fun `is registered as a built-in rate source quoting in roubles`() {
        val found = ServiceLoader.load(Plugin::class.java).filterIsInstance<CbrRatesPlugin>().single()
        (found as RateSource).sourceName shouldBe "ЦБ РФ"
        plugin.isQuoteCurrency(ruble) shouldBe true
        plugin.isQuoteCurrency(Currency(1, "Валюта", " rub ")) shouldBe true
        plugin.isQuoteCurrency(Currency(18, "Белорусский рубль", "BYR")) shouldBe false
        plugin.isQuoteCurrency(dollar) shouldBe false
    }

    @Test
    fun `parses nominal-scaled values and skips broken records`() {
        val xml = dynamic("R01805F", Triple("13.11.2026", 100, "78,1677"), Triple("14.11.2026", 100, "77,2483"), Triple("bad", 1, "1"), Triple("15.11.2026", 1, "oops"), Triple("16.11.2026", 0, "5"))
        CbrRatesPlugin.parseDynamic(xml) shouldBe listOf(
            LocalDate.of(2026, 11, 13) to BigDecimal("0.781677"),
            LocalDate.of(2026, 11, 14) to BigDecimal("0.772483"),
        )
        CbrRatesPlugin.parseDaily(daily) shouldBe mapOf("USD" to "R01235", "RSD" to "R01805F")
    }

    @Test
    fun `fetches by service id and by iso code, one request per currency`() = runTest {
        val host = Host { url ->
            when {
                "XML_daily" in url -> daily
                "R01235" in url -> dynamic("R01235", Triple("10.11.2026", 1, "80,0000"), Triple("11.11.2026", 1, "81,5000"))
                "R01805F" in url -> dynamic("R01805F", Triple("07.11.2026", 100, "78,0000"), Triple("10.11.2026", 100, "78,5443"))
                else -> error("unexpected $url")
            }
        }
        val result = plugin.fetch(host, listOf(ruble, dollar, dinar, hryvnia), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 15))

        result.unmatched shouldBe listOf(hryvnia)
        result.quotes.filter { it.currencyId == 10L }.map { it.date.dayOfMonth to it.value } shouldBe listOf(10 to BigDecimal("8E+1"), 11 to BigDecimal("81.5"))
        // The 7th is before the period and the 10th is in it: nothing is carried in from before.
        result.quotes.filter { it.currencyId == 20L }.map { it.date.dayOfMonth } shouldBe listOf(10)
        host.requests shouldHaveSize 3
        host.requests[0] shouldContain "XML_daily.asp?date_req=15/11/2026"
        host.requests[1] shouldContain "XML_dynamic.asp?date_req1=27/10/2026&date_req2=15/11/2026&VAL_NM_RQ=R01235"
    }

    @Test
    fun `a day without a quote yields the rate in force, under its own date`() = runTest {
        val host = Host { dynamic("R01235", Triple("13.11.2026", 1, "80,0000"), Triple("14.11.2026", 1, "81,0000")) }
        val sunday = LocalDate.of(2026, 11, 15)
        val result = plugin.fetch(host, listOf(dollar), sunday, sunday)

        result.quotes.single().date shouldBe LocalDate.of(2026, 11, 14)
        result.quotes.single().value.compareTo(BigDecimal("81")) shouldBe 0
        host.requests shouldHaveSize 1 // no ISO lookup needed when every currency carries a service id
    }

    @Test
    fun `nothing to ask for and a reversed period`() = runTest {
        val host = Host { error("no request expected") }
        plugin.fetch(host, listOf(ruble), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)).quotes.shouldBeEmpty()
        shouldThrow<IllegalArgumentException> { plugin.fetch(host, listOf(dollar), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)) }
    }
}
