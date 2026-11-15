// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import kalita.agent.api.AddDictionaryItemTool
import kalita.agent.api.AgentSession
import kalita.agent.api.CurrencyRate
import kalita.agent.api.DictionaryKind
import kalita.agent.api.WriteMode
import kalita.core.Dataset
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import kalita.plugin.api.RateSource
import kalita.plugin.host.PluginHostServices
import kalita.plugin.host.RateLoader
import kalita.plugin.host.RateLookup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.ServiceLoader

/** Finds the rate in force on a date for a currency about to be added; `null` when no rate source is installed. */
internal typealias NewCurrencyRateLookup = (dataset: Dataset, name: String, cbId: String?, date: LocalDate) -> RateLookup?

internal object NoPluginSecrets : PluginSecretStore {
    override fun get(key: String): String? = null
    override fun put(key: String, value: String) = Unit
    override fun remove(key: String) = Unit
}

/** The real lookup: the first built-in [RateSource] plugin over the JDK HTTP client. */
internal val internetRateLookup: NewCurrencyRateLookup = { dataset, name, cbId, date ->
    ServiceLoader.load(Plugin::class.java).firstOrNull { it is RateSource }?.let { plugin ->
        val host = PluginHostServices(JdkPluginHttpClient(), NoPluginSecrets, PluginLogger { _, _ -> }, database = { null }).forPlugin(plugin.descriptor.id)
        runBlocking { RateLoader.quoteForNewCurrency(plugin as RateSource, host, dataset, name, cbId, date) }
    }
}

/**
 * `add_dictionary_item` / `kalita dict add` with what a new **currency** needs to be usable at once
 * (dev_plan.md FR-R12): unless the caller gives a rate, the one in force on [date] is looked up and
 * stored with the currency in the same change set. A failed lookup never blocks the add — the result
 * then says why there is no rate (`rateNote`), so the agent can tell the person.
 */
@Suppress("LongParameterList")
internal fun addDictionaryItemWithRate(
    session: AgentSession,
    kind: DictionaryKind,
    name: String,
    parentId: Long?,
    cbId: String?,
    explicitRate: Double?,
    date: LocalDate,
    lookup: NewCurrencyRateLookup?,
    clientRequestId: String,
    device: String,
): JsonObject {
    var rate = explicitRate?.let { CurrencyRate(date, it) }
    var note: String? = null
    if (kind == DictionaryKind.Currency && rate == null && lookup != null) {
        when (val found = lookup(session.dataset, name, cbId, date)) {
            is RateLookup.Found -> {
                rate = CurrencyRate(found.date, found.value.toDouble())
                note = "Курс взят из источника «${found.sourceName}» на ${found.date}."
            }
            is RateLookup.Unavailable -> note = found.reason
            null -> Unit
        }
    }
    val result = AddDictionaryItemTool(session).execute(kind, name, parentId, cbId, WriteMode.Write, clientRequestId, device, rate)
    return if (note != null && result["rateNote"] == null) JsonObject(result + ("rateNote" to JsonPrimitive(note))) else result
}
