// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.host

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.core.AuditStamp
import kalita.core.Currency
import kalita.core.Dataset
import kalita.core.Rate
import kalita.plugin.api.PluginHost
import kalita.plugin.api.RateFetchResult
import kalita.plugin.api.RateQuote
import kalita.plugin.api.RateSource
import kalita.storage.api.ChangeSetBuilder
import kalita.storage.api.DbSnapshot
import kalita.storage.api.Op
import kalita.storage.api.Table
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class RateLoaderTest {
    private val ruble = Currency(12, "Рубль", null)
    private val dollar = Currency(10, "Доллар", "R01235")
    private val dinar = Currency(20, "RSD", null)
    private val gone = Currency(16, "Крона", "R01770", deleted = true)
    private val d10 = LocalDate.of(2026, 11, 10)
    private val d11 = LocalDate.of(2026, 11, 11)

    private class Source(private val result: (List<Currency>) -> RateFetchResult) : RateSource {
        var asked: List<Currency>? = null
        override val sourceName = "Банк"
        override val quoteCurrencyName = "рубль"
        override fun isQuoteCurrency(currency: Currency) = currency.name == "Рубль"
        override suspend fun fetch(host: PluginHost, currencies: List<Currency>, from: LocalDate, to: LocalDate): RateFetchResult {
            asked = currencies
            return result(currencies)
        }
    }

    private val host = PluginHostServices(NoNetworkHttpClient, MemorySecrets, { _, _ -> }, database = { null }).forPlugin("x")

    private object MemorySecrets : kalita.plugin.api.PluginSecretStore {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }

    @Test
    fun `new quotes are inserted, changed ones updated, equal ones left alone`() = runTest {
        val dataset = Dataset(
            currencies = listOf(ruble, dollar, dinar, gone),
            rates = listOf(Rate(1, 10, d10, 80.0), Rate(2, 10, d11, 81.0), Rate(3, 10, d11, 81.5)),
        )
        val source = Source {
            RateFetchResult(
                listOf(
                    RateQuote(10, d10, BigDecimal("80")), // as stored
                    RateQuote(10, d11, BigDecimal("82.25")), // differs from the newest row of that day (id 3)
                    RateQuote(20, d10, BigDecimal("0.785443")), // new
                    RateQuote(20, d10, BigDecimal("0.9")), // duplicate of the same day — ignored
                    RateQuote(16, d10, BigDecimal("7")), // not asked for
                    RateQuote(20, d11, BigDecimal.ZERO), // not a rate
                    RateQuote(20, d11.plusDays(30), BigDecimal.ONE), // after the period
                ),
                unmatched = emptyList(),
            )
        }
        val plan = RateLoader.plan(source, host, dataset, d10, d11).shouldBeInstanceOf<RateLoadResult.Plan>()

        source.asked shouldBe listOf(dollar, dinar.copy(cbId = "RSD"))
        plan.inserts shouldBe listOf(RateQuote(20, d10, BigDecimal("0.785443")))
        plan.updates shouldBe listOf(3L to RateQuote(10, d11, BigDecimal("82.25")))
        plan.unchanged shouldBe 1
        plan.isEmpty shouldBe false
        plan.quotes shouldHaveSize 2

        val now = LocalDateTime.of(2026, 11, 15, 12, 0)
        val snapshot = DbSnapshot(mapOf(Table.Rates to listOf(mapOf("id" to 3L, "Currency_ID" to 10L, "Date" to d11.atStartOfDay(), "Course" to 81.5, "del" to false))))
        val changeSet = ChangeSetBuilder(snapshot, AuditStamp(3, now), "desktop").also(plan::applyTo).build("rates")
        val insert = changeSet.ops[0].shouldBeInstanceOf<Op.Insert>()
        insert.table shouldBe Table.Rates
        insert.values["Currency_ID"] shouldBe 20L
        insert.values["Date"] shouldBe d10.atStartOfDay()
        insert.values["Course"] shouldBe 0.785443
        changeSet.ops[1].shouldBeInstanceOf<Op.Update>().fields.getValue("Course").new shouldBe 82.25
    }

    @Test
    fun `refuses a database that is not based on the source's quote currency`() = runTest {
        val source = Source { RateFetchResult(emptyList()) }

        RateLoader.plan(source, host, Dataset(currencies = listOf(dollar, dinar)), d10, d11)
            .shouldBeInstanceOf<RateLoadResult.Refused>().reason shouldContain "в справочнике валют такой нет"
        // The rouble itself has rates: something else is the base.
        RateLoader.plan(source, host, Dataset(currencies = listOf(ruble, dollar), rates = listOf(Rate(1, 12, d10, 0.012))), d10, d11)
            .shouldBeInstanceOf<RateLoadResult.Refused>().reason shouldContain "базовая валюта этой базы другая"
        // The only currency without rates is the dollar.
        RateLoader.plan(source, host, Dataset(currencies = listOf(ruble, dollar), rates = listOf(Rate(1, 12, d10, 0.012), Rate(2, 12, d11, 0.012))), d10, d11)
            .shouldBeInstanceOf<RateLoadResult.Refused>()
        RateLoader.plan(source, host, Dataset(currencies = listOf(ruble, dollar)), d11, d10)
            .shouldBeInstanceOf<RateLoadResult.Refused>().reason shouldContain "позже"
        source.asked shouldBe null
    }

    @Test
    fun `several currencies without rates do not block loading, unmatched ones are reported`() = runTest {
        val source = Source { RateFetchResult(emptyList(), unmatched = listOf(dinar)) }
        val plan = RateLoader.plan(source, host, Dataset(currencies = listOf(ruble, dollar, dinar)), d10, d11).shouldBeInstanceOf<RateLoadResult.Plan>()

        plan.isEmpty shouldBe true
        plan.unmatched shouldBe listOf(dinar)
        RateLoader.plan(source, host, Dataset(currencies = listOf(ruble)), d10, d11).shouldBeInstanceOf<RateLoadResult.Plan>().inserts.shouldBeEmpty()
    }

    @Test
    fun `a currency without a code is asked for under the code its name suggests`() = runTest {
        val named = Currency(21, "Сербский динар", null)
        val source = Source { RateFetchResult(emptyList()) }
        RateLoader.plan(source, host, Dataset(currencies = listOf(ruble, dollar, named)), d10, d11)

        source.asked!!.map { it.cbId } shouldBe listOf("R01235", "RSD")
    }

    @Test
    fun `the rate for a currency about to be added is the newest quote up to the date`() = runTest {
        val dataset = Dataset(currencies = listOf(ruble, dollar), rates = listOf(Rate(1, 10, d10, 80.0)))
        val found = Source { asked ->
            RateFetchResult(listOf(RateQuote(asked.single().id, d10, BigDecimal("0.78")), RateQuote(asked.single().id, d11, BigDecimal("0.77")), RateQuote(asked.single().id, d11.plusDays(1), BigDecimal.ONE)))
        }
        RateLoader.quoteForNewCurrency(found, host, dataset, "Сербский динар", null, d11) shouldBe RateLookup.Found(d11, BigDecimal("0.77"), "Банк")
        found.asked!!.single().cbId shouldBe "RSD"

        val unknown = Source { asked -> RateFetchResult(emptyList(), unmatched = asked) }
        RateLoader.quoteForNewCurrency(unknown, host, dataset, "Тугрик", null, d11).shouldBeInstanceOf<RateLookup.Unavailable>().reason shouldContain "не знает валюту"
        val silent = Source { RateFetchResult(emptyList()) }
        RateLoader.quoteForNewCurrency(silent, host, dataset, "Евро", "EUR", d11).shouldBeInstanceOf<RateLookup.Unavailable>().reason shouldContain "курса нет"
        val broken = Source { throw java.io.IOException("HTTP 503") }
        RateLoader.quoteForNewCurrency(broken, host, dataset, "Евро", "EUR", d11).shouldBeInstanceOf<RateLookup.Unavailable>().reason shouldContain "HTTP 503"

        // The source's own currency needs no rate; a database on another base gets none.
        RateLoader.quoteForNewCurrency(found, host, dataset, "Рубль", null, d11).shouldBeInstanceOf<RateLookup.Unavailable>()
        RateLoader.quoteForNewCurrency(found, host, Dataset(currencies = listOf(dollar)), "Евро", "EUR", d11).shouldBeInstanceOf<RateLookup.Unavailable>()
    }
}
