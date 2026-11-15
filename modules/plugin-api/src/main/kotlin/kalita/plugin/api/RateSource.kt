// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import kalita.core.Currency
import java.math.BigDecimal
import java.time.LocalDate

/** Value of one unit of the currency [currencyId] (a `Currency.id` of the open database) on [date], in the source's quote currency. */
data class RateQuote(
    val currencyId: Long,
    val date: LocalDate,
    val value: BigDecimal,
)

/**
 * What a [RateSource] found for a request. [unmatched] are the requested currencies the source
 * could not identify (no code it knows in `CB_ID` or the name) — the shell lists them so the
 * user can fix the currency's code rather than wonder why nothing arrived.
 */
data class RateFetchResult(
    val quotes: List<RateQuote>,
    val unmatched: List<Currency> = emptyList(),
)

/**
 * Extension point: exchange rates from the Internet (the original's «Сервис → Курсы из
 * Интернета», dev_plan.md FR-R12). The plugin only fetches — the host decides what to write to
 * `Course` and writes it as one change set (§9.11: plugins never write to the database).
 *
 * Every source quotes in one currency ([quoteCurrencyName]); the host loads rates only into a
 * database whose base currency is that one, recognised with [isQuoteCurrency].
 */
interface RateSource {
    /** Short name for the window title and messages, e.g. «ЦБ РФ». */
    val sourceName: String

    /** The currency the rates are quoted in, as the user would call it, e.g. «российский рубль». */
    val quoteCurrencyName: String

    /** Whether [currency] of the user's dictionary is the source's quote currency. */
    fun isQuoteCurrency(currency: Currency): Boolean

    /**
     * Rates of [currencies] effective within [from]..[to]. When a currency has no quote dated
     * exactly [from], the last one before it is included under its own date, so a request for a
     * weekend still yields the rate in force. Throws on a network or format failure.
     */
    suspend fun fetch(host: PluginHost, currencies: List<Currency>, from: LocalDate, to: LocalDate): RateFetchResult
}
