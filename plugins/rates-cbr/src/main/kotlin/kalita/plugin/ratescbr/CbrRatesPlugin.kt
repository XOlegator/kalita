// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.ratescbr

import kalita.core.Currency
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginDescriptor
import kalita.plugin.api.PluginHost
import kalita.plugin.api.PluginLogLevel
import kalita.plugin.api.PluginVersion
import kalita.plugin.api.RateFetchResult
import kalita.plugin.api.RateQuote
import kalita.plugin.api.RateSource
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Official rates of the Bank of Russia from its public XML service (`cbr.ru/scripts`, no key, no
 * registration) — the same source the original X-Cash used, whose `Currency.CB_ID` values are this
 * service's own currency ids (`R01235` = US dollar).
 *
 * A currency of the user's dictionary is identified, in this order, by `CB_ID` being a service id
 * (`R01235`), `CB_ID` being an ISO code (`USD`), or the name being an ISO code (`RSD`). ISO codes are
 * resolved through the daily list of the last requested day; history then comes from the
 * per-currency «dynamic» request, one HTTP call per currency whatever the length of the period.
 */
class CbrRatesPlugin : Plugin, RateSource {
    override val descriptor = PluginDescriptor(
        id = ID,
        displayName = "Курсы валют ЦБ РФ",
        version = PluginVersion(1, 0, 0),
        requiredApiVersion = PluginVersion(1, 2, 0),
    )

    override val sourceName = "ЦБ РФ"
    override val quoteCurrencyName = "российский рубль"

    override fun isQuoteCurrency(currency: Currency): Boolean =
        currency.cbId?.trim()?.uppercase() in RUBLE_CODES || currency.name?.trim()?.lowercase() in RUBLE_NAMES

    override suspend fun fetch(host: PluginHost, currencies: List<Currency>, from: LocalDate, to: LocalDate): RateFetchResult {
        require(!from.isAfter(to)) { "Начало периода позже его конца" }
        val wanted = currencies.filterNot(::isQuoteCurrency)
        if (wanted.isEmpty()) return RateFetchResult(emptyList())

        val needIsoLookup = wanted.any { serviceId(it) == null }
        val idByIsoCode = if (needIsoLookup) parseDaily(host.httpClient.get("$BASE/XML_daily.asp?date_req=${to.format(REQUEST_DATE)}")) else emptyMap()

        val quotes = mutableListOf<RateQuote>()
        val unmatched = mutableListOf<Currency>()
        // The service has no quote for days the bank set none (weekends, holidays); asking from a
        // little earlier lets the rate in force on `from` be found.
        val queryFrom = from.minusDays(LOOK_BACK_DAYS)
        for (currency in wanted) {
            val id = serviceId(currency) ?: isoCandidates(currency).firstNotNullOfOrNull { idByIsoCode[it] }
            if (id == null) {
                unmatched += currency
                continue
            }
            val url = "$BASE/XML_dynamic.asp?date_req1=${queryFrom.format(REQUEST_DATE)}&date_req2=${to.format(REQUEST_DATE)}&VAL_NM_RQ=$id"
            val records = parseDynamic(host.httpClient.get(url))
            val inRange = records.filter { !it.first.isBefore(from) && !it.first.isAfter(to) }
            val inForceAtStart = records.filter { it.first.isBefore(from) }.maxByOrNull { it.first }
                .takeIf { inRange.none { r -> r.first == from } }
            (listOfNotNull(inForceAtStart) + inRange).mapTo(quotes) { (date, value) -> RateQuote(currency.id, date, value) }
            host.logger.log(PluginLogLevel.Debug, "$id: ${records.size} record(s)")
        }
        return RateFetchResult(quotes, unmatched)
    }

    companion object {
        const val ID = "rates-cbr"
        const val BASE = "https://www.cbr.ru/scripts"
        private const val LOOK_BACK_DAYS = 14L

        private val REQUEST_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val RESPONSE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private val RUBLE_CODES = setOf("RUB", "RUR", "R00000")
        private val RUBLE_NAMES = setOf("рубль", "рубли", "руб", "руб.", "российский рубль", "rub", "rur")

        private val SERVICE_ID = Regex("""R\d{5}[A-Z]?""")
        private val ISO_CODE = Regex("""[A-Z]{3}""")
        private val VALUTE = Regex("""<Valute ID="([^"]+)">(.*?)</Valute>""", RegexOption.DOT_MATCHES_ALL)
        private val RECORD = Regex("""<Record Date="([^"]+)"[^>]*>(.*?)</Record>""", RegexOption.DOT_MATCHES_ALL)

        private fun tag(xml: String, name: String): String? = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim()

        private fun number(text: String?): BigDecimal? = text?.replace(',', '.')?.replace(" ", "")?.toBigDecimalOrNull()

        internal fun serviceId(currency: Currency): String? =
            currency.cbId?.trim()?.uppercase()?.takeIf(SERVICE_ID::matches)

        internal fun isoCandidates(currency: Currency): List<String> =
            listOfNotNull(currency.cbId, currency.name).map { it.trim().uppercase() }.filter(ISO_CODE::matches)

        /** ISO code → service id from an `XML_daily.asp` response. */
        internal fun parseDaily(xml: String): Map<String, String> =
            VALUTE.findAll(xml).mapNotNull { m -> tag(m.groupValues[2], "CharCode")?.let { it.uppercase() to m.groupValues[1] } }.toMap()

        /** Date → value of one unit, from an `XML_dynamic.asp` response (the service quotes some currencies per 10, 100, …). */
        internal fun parseDynamic(xml: String): List<Pair<LocalDate, BigDecimal>> =
            RECORD.findAll(xml).mapNotNull { m ->
                val date = runCatching { LocalDate.parse(m.groupValues[1], RESPONSE_DATE) }.getOrNull() ?: return@mapNotNull null
                val value = number(tag(m.groupValues[2], "Value")) ?: return@mapNotNull null
                val nominal = number(tag(m.groupValues[2], "Nominal")) ?: BigDecimal.ONE
                if (value.signum() <= 0 || nominal.signum() <= 0) return@mapNotNull null
                date to value.divide(nominal, MathContext.DECIMAL64).stripTrailingZeros()
            }.toList()
    }
}
