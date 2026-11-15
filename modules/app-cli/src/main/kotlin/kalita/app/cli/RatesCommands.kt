// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kalita.agent.api.AgentSession
import kalita.core.Dataset
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginHttpClient
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import kalita.plugin.api.RateSource
import kalita.plugin.host.PluginHostServices
import kalita.plugin.host.RateLoadResult
import kalita.plugin.host.RateLoader
import kalita.storage.api.SubmitResult
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.ServiceLoader

/** `kalita rates` — parent of `load` (dev_plan.md FR-R12, the original's «Сервис → Курсы из Интернета»). */
internal class RatesCommand : CliktCommand(name = "rates") {
    override fun run() = Unit
}

/**
 * `kalita rates load --db <path> [--from yyyy-MM-dd] [--to yyyy-MM-dd] [--currency <name>]… [--source <plugin id>] [--dry-run]` —
 * fetches exchange rates from a [RateSource] plugin and writes the new and changed ones to `Course` as one
 * change set. Both dates default to today; a day without a quote yields the rate in force on it.
 */
internal class RatesLoadCommand(
    private val out: PrintStream,
    private val err: PrintStream,
    private val plugins: () -> List<Plugin> = { ServiceLoader.load(Plugin::class.java).toList() },
    private val httpClient: () -> PluginHttpClient = { JdkPluginHttpClient() },
    private val today: () -> LocalDate = { LocalDate.now() },
) : CliktCommand(name = "load") {
    private val dbPath by option("--db").required()
    private val fromArg by option("--from")
    private val toArg by option("--to")
    private val sourceId by option("--source")
    private val currencyNames by option("--currency").multiple()
    private val dryRun by option("--dry-run").flag()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val to = parseDate(toArg, "--to") ?: today()
        val from = parseDate(fromArg, "--from") ?: to
        val sources = plugins().filter { it is RateSource && (sourceId == null || it.descriptor.id == sourceId) }
        val plugin = sources.firstOrNull() ?: run {
            err.println(if (sourceId == null) "no rate source plugin is available" else "no such rate source: $sourceId")
            throw ProgramResult(2)
        }
        val source = plugin as RateSource
        val host = PluginHostServices(httpClient(), NoSecrets, PluginLogger { _, _ -> }, database = { null }).forPlugin(plugin.descriptor.id)

        AgentSession.open(file).use { session ->
            val dataset = session.dataset
            val result = try {
                runBlocking { RateLoader.plan(source, host, dataset, from, to) }
            } catch (e: Exception) {
                err.println("Не удалось получить курсы (${source.sourceName}): ${e.message ?: e}")
                throw ProgramResult(1)
            }
            val plan = when (result) {
                is RateLoadResult.Refused -> {
                    err.println(result.reason)
                    throw ProgramResult(1)
                }
                is RateLoadResult.Plan -> result
            }.let { only(it, dataset) }
            val names = dataset.currencies.associate { it.id to (it.name ?: "id:${it.id}") }
            plan.quotes.groupBy { it.currencyId }.forEach { (currencyId, quotes) ->
                val last = quotes.maxBy { it.date }
                out.println("${names[currencyId]}: ${quotes.size} курс(ов), последний ${last.date} = ${last.value.toPlainString()}")
            }
            if (plan.unmatched.isNotEmpty()) {
                out.println("Нет в источнике (проверьте код валюты): ${plan.unmatched.joinToString { it.name ?: "id:${it.id}" }}")
            }
            out.println("${source.sourceName}: новых ${plan.inserts.size}, изменённых ${plan.updates.size}, без изменений ${plan.unchanged}.")
            if (dryRun || plan.isEmpty) return
            when (val submitted = session.submit("Загрузка курсов валют (${source.sourceName})", RATES_DEVICE) { plan.applyTo(it) }.result) {
                is SubmitResult.Applied -> out.println("Записано в базу.")
                is SubmitResult.Conflicts -> {
                    err.println("Не записано: база изменилась, пока шла загрузка (${submitted.conflicts.size}). Повторите команду.")
                    throw ProgramResult(1)
                }
                is SubmitResult.Failed -> {
                    err.println("Не записано: ${submitted.reason}")
                    throw ProgramResult(1)
                }
            }
        }
    }

    /** Narrows [plan] to the currencies named with `--currency` (all of them when the option is absent). */
    private fun only(plan: RateLoadResult.Plan, dataset: Dataset): RateLoadResult.Plan {
        if (currencyNames.isEmpty()) return plan
        val ids = currencyNames.map { name ->
            dataset.currencies.firstOrNull { !it.deleted && it.name.equals(name, ignoreCase = true) }?.id ?: run {
                err.println("no such currency: $name")
                throw ProgramResult(2)
            }
        }.toSet()
        return plan.copy(
            inserts = plan.inserts.filter { it.currencyId in ids },
            updates = plan.updates.filter { it.second.currencyId in ids },
            unmatched = plan.unmatched.filter { it.id in ids },
        )
    }

    private fun parseDate(text: String?, option: String): LocalDate? = text?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            err.println("$option: expected yyyy-MM-dd, got $it")
            throw ProgramResult(2)
        }
    }

    private object NoSecrets : PluginSecretStore {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }

    private companion object {
        const val RATES_DEVICE = "cli:rates"
    }
}
