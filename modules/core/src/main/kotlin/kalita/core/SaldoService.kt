// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal

/** One line of the «Остатки» panel: an account in one currency (dev_plan.md §5.3). */
data class SaldoLine(
    val accountId: Long,
    val currencyId: Long?,
    /** «Получено»: Σ `Summa` of operations into the account. */
    val received: BigDecimal,
    /** «Потрачено»: Σ `Summa` of operations out of the account. */
    val spent: BigDecimal,
) {
    /** «Остаток». */
    val balance: BigDecimal get() = received.subtract(spent)
}

/** «ИТОГО» in the reduction currency at the latest rates. */
data class SaldoTotal(
    val received: BigDecimal,
    val spent: BigDecimal,
    /** Currencies left out of the total for lack of any rate; non-empty means the total is incomplete. */
    val currenciesWithoutRate: Set<Long?>,
) {
    val balance: BigDecimal get() = received.subtract(spent)
}

data class Saldo(val lines: List<SaldoLine>, val total: SaldoTotal)

/**
 * Account balances per (account, currency) over the live operations (dev_plan.md §5.3). A transfer
 * counts as spent on its source and received on its target; an operation's type does not matter,
 * only which account fields it fills.
 */
object SaldoService {
    fun compute(dataset: Dataset, rates: RateService, reductionCurrencyId: Long?): Saldo {
        val received = LinkedHashMap<Pair<Long, Long?>, BigDecimal>()
        val spent = LinkedHashMap<Pair<Long, Long?>, BigDecimal>()
        for (op in dataset.operations) {
            if (op.deleted) continue
            val amount = op.amount ?: continue
            op.accountTo?.let { received.merge(it to op.currencyId, amount, BigDecimal::add) }
            op.accountFrom?.let { spent.merge(it to op.currencyId, amount, BigDecimal::add) }
        }
        val lines = (received.keys + spent.keys)
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { key ->
                SaldoLine(key.first, key.second, received[key] ?: BigDecimal.ZERO, spent[key] ?: BigDecimal.ZERO)
            }

        var totalReceived = BigDecimal.ZERO
        var totalSpent = BigDecimal.ZERO
        val withoutRate = LinkedHashSet<Long?>()
        for (line in lines) {
            val rate = rates.latestCross(line.currencyId, reductionCurrencyId)
            if (rate == null) {
                withoutRate += line.currencyId
                continue
            }
            totalReceived += line.received.multiply(rate)
            totalSpent += line.spent.multiply(rate)
        }
        return Saldo(lines, SaldoTotal(totalReceived, totalSpent, withoutRate))
    }
}
