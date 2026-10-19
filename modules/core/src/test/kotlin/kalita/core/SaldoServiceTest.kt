// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kalita.core.DemoData.BANK
import kalita.core.DemoData.CHILD
import kalita.core.DemoData.HUSBAND
import kalita.core.DemoData.MAIN
import kalita.core.DemoData.RUB
import kalita.core.DemoData.USD
import kalita.core.DemoData.WIFE
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class SaldoServiceTest {
    private val dataset = DemoData.dataset
    private val rates = RateService.of(dataset)

    private fun BigDecimal.cents() = setScale(2, RoundingMode.HALF_UP)

    @Test
    fun `balances per account and currency match the demo panel`() {
        val saldo = SaldoService.compute(dataset, rates, RUB)

        // dev_plan.md §5.3: (account, currency, received, spent); the deleted expense of Wife is ignored.
        saldo.lines.map { listOf(it.accountId, it.currencyId, it.received.cents(), it.spent.cents(), it.balance.cents()) } shouldContainExactlyInAnyOrder listOf(
            listOf(WIFE, RUB, BigDecimal("2000.00"), BigDecimal("0.00"), BigDecimal("2000.00")),
            listOf(HUSBAND, USD, BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("0.00")),
            listOf(HUSBAND, RUB, BigDecimal("3860.00"), BigDecimal("3060.00"), BigDecimal("800.00")),
            listOf(MAIN, USD, BigDecimal("1000.00"), BigDecimal("100.00"), BigDecimal("900.00")),
            listOf(MAIN, RUB, BigDecimal("7860.00"), BigDecimal("0.00"), BigDecimal("7860.00")),
            listOf(CHILD, RUB, BigDecimal("200.00"), BigDecimal("0.00"), BigDecimal("200.00")),
            listOf(BANK, RUB, BigDecimal("50000.00"), BigDecimal("0.00"), BigDecimal("50000.00")),
        )
    }

    @Test
    fun `total is in the reduction currency at the latest rates`() {
        val total = SaldoService.compute(dataset, rates, RUB).total

        total.received.cents() shouldBe BigDecimal("93548.61")
        total.spent.cents() shouldBe BigDecimal("8447.02")
        total.balance.cents() shouldBe BigDecimal("85101.59")
        total.currenciesWithoutRate shouldBe emptySet()
    }

    @Test
    fun `currencies without any rate are reported, not silently summed`() {
        val data = Dataset(
            operations = listOf(
                Operation(1, DemoData.jan1, BigDecimal("10"), RUB, null, null, accountTo = MAIN),
                Operation(2, DemoData.jan1, BigDecimal("7"), 999, null, null, accountTo = MAIN),
                Operation(3, DemoData.jan1, null, RUB, null, null, accountTo = MAIN),
            ),
        )
        val saldo = SaldoService.compute(data, RateService(emptyList(), RUB), RUB)

        saldo.lines.size shouldBe 2
        saldo.total.received shouldBe BigDecimal("10")
        saldo.total.currenciesWithoutRate shouldBe setOf(999L)
    }
}
