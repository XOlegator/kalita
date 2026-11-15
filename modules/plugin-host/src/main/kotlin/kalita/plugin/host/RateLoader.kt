// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.host

import kalita.core.Currency
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
        val live = dataset.currencies.filter { !it.deleted }
        val quoteIds = live.filter(source::isQuoteCurrency).mapTo(HashSet()) { it.id }
        if (quoteIds.isEmpty()) {
            return RateLoadResult.Refused("${source.sourceName} задаёт курсы в валюте «${source.quoteCurrencyName}», а в справочнике валют такой нет.")
        }
        // Rates in `Course` are quoted in the base currency, which has none of its own (§5.2). A
        // database that keeps rates *for* the source's quote currency is based on something else,
        // and the source's numbers would be wrong in it.
        val liveRates = dataset.rates.filter { !it.deleted }
        val base = dataset.detectBaseCurrency()
        if (liveRates.any { it.currencyId in quoteIds } || (base != null && base !in quoteIds)) {
            return RateLoadResult.Refused(
                "${source.sourceName} задаёт курсы в валюте «${source.quoteCurrencyName}», а базовая валюта этой базы другая — такие курсы в неё загружать нельзя.",
            )
        }
        val targets = live.filter { it.id !in quoteIds }
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
}
