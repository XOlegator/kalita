// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import kalita.core.JournalRow
import kalita.core.JournalValues
import kalita.core.Operation
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class JournalDayGroupingTest {
    private fun row(id: Long, date: LocalDate?, converted: BigDecimal?) =
        JournalRow(
            Operation(id = id, date = date, amount = BigDecimal.TEN, currencyId = 1, budgetItemId = null, typeId = null),
            JournalValues(signedAmount = BigDecimal.TEN, rate = BigDecimal.ONE, convertedAmount = converted),
        )

    @Test
    fun `rows on the same day collapse into one group with a summed total`() {
        val day1 = LocalDate.of(2026, 11, 7)
        val rows = listOf(
            row(1, day1, BigDecimal("100")),
            row(2, day1, BigDecimal("50")),
        )

        val groups = groupByDay(rows)

        groups.size shouldBe 1
        groups[0].date shouldBe day1
        groups[0].rows.map { it.operation.id } shouldBe listOf(1L, 2L)
        groups[0].total shouldBe BigDecimal("150")
    }

    @Test
    fun `consecutive different days stay separate groups, in the order they arrive`() {
        val day1 = LocalDate.of(2026, 11, 7)
        val day2 = LocalDate.of(2026, 11, 6)
        val rows = listOf(row(1, day1, BigDecimal.ONE), row(2, day2, BigDecimal.ONE))

        val groups = groupByDay(rows)

        groups.map { it.date } shouldBe listOf(day1, day2)
    }

    @Test
    fun `a day where every row is missing a rate gets a null total, not a false zero`() {
        val day1 = LocalDate.of(2026, 11, 7)
        val groups = groupByDay(listOf(row(1, day1, null), row(2, day1, null)))

        groups[0].total shouldBe null
    }

    @Test
    fun `an operation with no date groups under the null key`() {
        val groups = groupByDay(listOf(row(1, null, BigDecimal.ONE)))

        groups[0].date shouldBe null
    }
}
