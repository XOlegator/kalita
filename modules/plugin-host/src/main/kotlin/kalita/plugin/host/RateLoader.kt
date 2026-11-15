// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.host

import kalita.core.Currency
import kalita.core.CurrencyCatalog
import kalita.core.Dataset
import kalita.plugin.api.PluginHost
import kalita.plugin.api.RateQuote
import kalita.plugin.api.RateSource
import kalita.storage.api.ChangeSetBuilder
import kalita.storage.api.Table
import java.math.BigDecimal
import java.time.LocalDate

/** Outcome of asking a [RateSource] for rates: either a reason nothing can be loaded, or what would be written. */
sealed interface RateLoadResult {
    data class Refused(val reason: String) : RateLoadResult

    /**
     * [inserts] are quotes the database has no rate for on that day, [updates] pair an existing
     * `Course.id` with a quote whose value differs, [unchanged] counts quotes already stored as
     * they are. [unmatched] are currencies the source did not recognise.
     */
    data class Plan(
        val inserts: List<RateQuote>,
        val updates: List<Pair<Long, RateQuote>>,
        val unchanged: Int,
        val unmatched: List<Currency>,
    ) : RateLoadResult {
        val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty()

        /** Every quote that changes the database. */
        val quotes: List<RateQuote> get() = inserts + updates.map { it.second }

        /** Adds the plan to [builder] — the caller submits it as one change set, undoable as one. */
        fun applyTo(builder: ChangeSetBuilder) {
            for (q in inserts) builder.insert(Table.Rates, builder.nextTempKey(), values(q))
            for ((id, q) in updates) builder.update(Table.Rates, id, values(q))
        }

        private fun values(q: RateQuote): Map<String, Any?> = mapOf(
            "Currency_ID" to q.currencyId,
            "Date" to q.date.atStartOfDay(),
            "Course" to q.value.toDouble(),
        )
    }
}

/**
 * The host side of «Курсы из Интернета» (dev_plan.md FR-R12), shared by every shell: checks that
 * the source's quote currency is this database's base currency, fetches, and compares with the
 * rates already stored. Nothing is written here (§9.11) — see [RateLoadResult.Plan.applyTo].
 */
object RateLoader {
    suspend fun plan(source: RateSource, host: PluginHost, dataset: Dataset, from: LocalDate, to: LocalDate): RateLoadResult {
        if (from.isAfter(to)) return RateLoadResult.Refused("Начало периода позже его конца.")
        refusal(source, dataset)?.let { return RateLoadResult.Refused(it) }
        val live = dataset.currencies.filter { !it.deleted }
        val quoteIds = live.filter(source::isQuoteCurrency).mapTo(HashSet()) { it.id }
        val liveRates = dataset.rates.filter { !it.deleted }
        // A currency entered without a code is still asked for under the code its name suggests.
        val targets = live.filter { it.id !in quoteIds }
            .map { if (it.cbId.isNullOrBlank()) it.copy(cbId = CurrencyCatalog.codeFor(it.name)) else it }
        if (targets.isEmpty()) return RateLoadResult.Plan(emptyList(), emptyList(), 0, emptyList())

        val fetched = source.fetch(host, targets, from, to)
        val targetIds = targets.mapTo(HashSet()) { it.id }
        val stored = liveRates.filter { it.currencyId != null && it.date != null }
            .groupBy { it.currencyId!! to it.date!! }
            .mapValues { (_, rows) -> rows.maxBy { it.id } }

        val inserts = mutableListOf<RateQuote>()
        val updates = mutableListOf<Pair<Long, RateQuote>>()
        var unchanged = 0
        val seen = HashSet<Pair<Long, LocalDate>>()
        for (q in fetched.quotes) {
            if (q.currencyId !in targetIds || q.value.signum() <= 0 || q.date.isAfter(to) || !seen.add(q.currencyId to q.date)) continue
            val existing = stored[q.currencyId to q.date]
            when {
                existing == null -> inserts += q
                existing.value != null && BigDecimal.valueOf(existing.value!!).compareTo(BigDecimal.valueOf(q.value.toDouble())) == 0 -> unchanged++
                else -> updates += existing.id to q
            }
        }
        return RateLoadResult.Plan(inserts, updates, unchanged, fetched.unmatched)
    }

    /** Why [source]'s rates do not belong in [dataset], `null` when they do. */
    private fun refusal(source: RateSource, dataset: Dataset): String? {
        val live = dataset.currencies.filter { !it.deleted }
        val quoteIds = live.filter(source::isQuoteCurrency).mapTo(HashSet()) { it.id }
        if (quoteIds.isEmpty()) {
            return "${source.sourceName} задаёт курсы в валюте «${source.quoteCurrencyName}», а в справочнике валют такой нет."
        }
        // Rates in `Course` are quoted in the base currency, which has none of its own (§5.2). A
        // database that keeps rates *for* the source's quote currency is based on something else,
        // and the source's numbers would be wrong in it.
        val base = dataset.detectBaseCurrency()
        if (dataset.rates.any { !it.deleted && it.currencyId in quoteIds } || (base != null && base !in quoteIds)) {
            return "${source.sourceName} задаёт курсы в валюте «${source.quoteCurrencyName}», а базовая валюта этой базы другая — такие курсы в неё загружать нельзя."
        }
        return null
    }

    /**
     * The rate in force on [date] for a currency that is not in the database yet — what «Добавить
     * валюту» offers to store with it, so the new currency is usable at once. Never throws: a
     * source that does not know the currency, the wrong base currency and a network failure all
     * come back as [RateLookup.Unavailable] with a reason the user can read.
     */
    suspend fun quoteForNewCurrency(source: RateSource, host: PluginHost, dataset: Dataset, name: String, cbId: String?, date: LocalDate): RateLookup {
        refusal(source, dataset)?.let { return RateLookup.Unavailable(it) }
        val candidate = Currency(NEW_CURRENCY_ID, name, CurrencyCatalog.codeOrSuggestion(name, cbId))
        if (source.isQuoteCurrency(candidate)) return RateLookup.Unavailable("Это валюта, в которой ${source.sourceName} задаёт курсы, — курс ей не нужен.")
        return try {
            val fetched = source.fetch(host, listOf(candidate), date, date)
            val quote = fetched.quotes.filter { it.value.signum() > 0 && !it.date.isAfter(date) }.maxByOrNull { it.date }
            when {
                quote != null -> RateLookup.Found(quote.date, quote.value, source.sourceName)
                fetched.unmatched.isNotEmpty() -> RateLookup.Unavailable("${source.sourceName} не знает валюту «$name» — укажите её код (например, USD).")
                else -> RateLookup.Unavailable("${source.sourceName}: на эту дату курса нет.")
            }
        } catch (e: Exception) {
            RateLookup.Unavailable("Не удалось получить курс (${source.sourceName}): ${e.message ?: e}")
        }
    }

    private const val NEW_CURRENCY_ID = Long.MIN_VALUE
}

/** Outcome of [RateLoader.quoteForNewCurrency]. */
sealed interface RateLookup {
    data class Found(val date: LocalDate, val value: BigDecimal, val sourceName: String) : RateLookup

    data class Unavailable(val reason: String) : RateLookup
}
