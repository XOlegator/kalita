// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.shouldBe
import kalita.core.DemoData.RUB
import kalita.core.DemoData.USD
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class JournalColumnsTest {
    private val dataset = DemoData.dataset
    private val rates = RateService.of(dataset)

    private fun BigDecimal?.cents() = this?.setScale(2, RoundingMode.HALF_UP)

    @Test
    fun `computed columns match the demo journal in roubles`() {
        val columns = JournalColumns(dataset, rates, RUB)
        val rows = DemoData.operations.map(columns::values)

        // dev_plan.md §5.2, row by row as on the demo screenshot.
        rows.map { it.signedAmount.cents() } shouldBe listOf(
            "100.00", "-100.00", "2860.00", "2860.00", "200.00", "1000.00", "5000.00", "1000.00", "2000.00", "50000.00",
        ).map(::BigDecimal)
        rows.map { it.rate.cents() } shouldBe listOf(
            "27.98", "27.98", "1.00", "1.00", "1.00", "28.78", "1.00", "1.00", "1.00", "1.00",
        ).map(::BigDecimal)
        rows.map { it.convertedAmount.cents() } shouldBe listOf(
            "2798.05", "-2798.05", "2860.00", "2860.00", "200.00", "28782.50", "5000.00", "1000.00", "2000.00", "50000.00",
        ).map(::BigDecimal)
    }

    @Test
    fun `footer totals match the demo journal`() {
        val columns = JournalColumns(dataset, rates, RUB)
        val rows = DemoData.operations.map(columns::values)

        aggregate(rows.map { it.signedAmount }, Aggregate.Sum) shouldBe BigDecimal("64920")
        aggregate(rows.map { it.convertedAmount }, Aggregate.Sum).cents() shouldBe BigDecimal("92702.50")
        aggregate(rows.map { it.rate }, Aggregate.Sum).cents() shouldBe BigDecimal("91.74")
        aggregate(rows.map { it.rate }, Aggregate.Count) shouldBe BigDecimal(10)
    }

    @Test
    fun `changing the reduction currency recomputes rate and converted amount`() {
        val columns = JournalColumns(dataset, rates, USD)
        val transfer = columns.values(DemoData.operations[0])
        transfer.rate shouldBe BigDecimal.ONE
        transfer.convertedAmount shouldBe BigDecimal("100")

        val roubles = columns.values(DemoData.operations[2])
        roubles.signedAmount shouldBe BigDecimal("2860")
        // 2860 RUB at 27.9805 RUB/USD ≈ 102.21 USD.
        roubles.convertedAmount.cents() shouldBe BigDecimal("102.21")
    }

    @Test
    fun `missing inputs leave the computed cells empty`() {
        val columns = JournalColumns(dataset, rates, RUB)
        val noAmount = Operation(1, DemoData.jan1, null, USD, null, DemoData.EXPENSE)
        columns.values(noAmount) shouldBe JournalValues(null, BigDecimal("28.7825"), null)

        val beforeFirstRate = Operation(2, DemoData.jan1.minusDays(1), BigDecimal("5"), USD, null, DemoData.EXPENSE)
        columns.values(beforeFirstRate) shouldBe JournalValues(BigDecimal("-5"), null, null)

        // Unknown type counts as positive, like a transfer.
        val unknownType = Operation(3, DemoData.jan1, BigDecimal("5"), RUB, null, null)
        columns.values(unknownType).signedAmount shouldBe BigDecimal("5")
    }

    @Test
    fun `aggregates over empty and partial columns`() {
        val values = listOf(BigDecimal("3"), null, BigDecimal("-1"), BigDecimal("4"))
        aggregate(values, Aggregate.Sum) shouldBe BigDecimal("6")
        aggregate(values, Aggregate.Min) shouldBe BigDecimal("-1")
        aggregate(values, Aggregate.Max) shouldBe BigDecimal("4")
        aggregate(values, Aggregate.Average) shouldBe BigDecimal("2")
        aggregate(values, Aggregate.Count) shouldBe BigDecimal(3)
        Aggregate.entries.filter { it != Aggregate.Count }.forEach { aggregate(listOf(null), it) shouldBe null }
        aggregate(emptyList(), Aggregate.Count) shouldBe BigDecimal.ZERO
    }
}
