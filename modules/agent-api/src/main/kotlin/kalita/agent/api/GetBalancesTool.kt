// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.Dataset
import kalita.core.RateService
import kalita.core.SaldoService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `get_balances` (dev_plan.md §12.13): wraps [SaldoService] — balances per account/currency and ИТОГО. */
class GetBalancesTool(private val dataset: Dataset) {
    fun execute(reductionCurrencyId: Long? = RateService.of(dataset).baseCurrencyId): JsonObject {
        val rates = RateService.of(dataset)
        val saldo = SaldoService.compute(dataset, rates, reductionCurrencyId)
        return buildJsonObject {
            put(
                "lines",
                JsonArray(
                    saldo.lines.map { line ->
                        buildJsonObject {
                            put("account", dataset.account(line.accountId)?.name)
                            put("currency", dataset.currency(line.currencyId)?.name)
                            putAmount("received", line.received)
                            putAmount("spent", line.spent)
                            putAmount("balance", line.balance)
                        }
                    },
                ),
            )
            put(
                "total",
                buildJsonObject {
                    putAmount("received", saldo.total.received)
                    putAmount("spent", saldo.total.spent)
                    putAmount("balance", saldo.total.balance)
                    put(
                        "currenciesWithoutRate",
                        JsonArray(saldo.total.currenciesWithoutRate.map { JsonPrimitive(dataset.currency(it)?.name) }),
                    )
                },
            )
        }
    }
}
