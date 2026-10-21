// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kalita.core.DemoData.CHILD
import kalita.core.DemoData.EXCHANGERS
import kalita.core.DemoData.HUSBAND
import kalita.core.DemoData.MAIN
import kalita.core.DemoData.ROOT_INCOME
import kalita.core.DemoData.RUB
import kalita.core.DemoData.TRANSFER
import kalita.core.DemoData.USD
import kalita.core.DemoData.jan1
import kalita.core.DemoData.jan26
import kalita.core.DemoData.jan6
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class JournalQueryTest {
    private val dataset = DemoData.dataset
    private val query = JournalQuery(dataset, RateService.of(dataset))
    private val midJanuary = LocalDate.of(2006, 1, 15)

    // Demo operations get ids 100..109 in DemoData order; 110 is the soft-deleted one.
    private fun ids(filter: JournalFilter, sort: List<SortKey> = emptyList(), showDeleted: Boolean = false) =
        query.run(filter, sort, RUB, midJanuary, showDeleted).map { it.operation.id }

    private fun ids(vararg conditions: FilterCondition) = ids(JournalFilter(conditions.toList()))

    private fun range(vararg ids: Int) = ids.map(Int::toLong)

    @Test
    fun `no filter shows the live operations, deleted ones on request`() {
        ids(JournalFilter.NONE) shouldBe (100L..109L).toList()
        ids(JournalFilter.NONE, showDeleted = true) shouldBe (100L..110L).toList()
    }

    @Test
    fun `the default filter is the current month`() {
        ids(JournalFilter.CURRENT_MONTH) shouldBe (100L..109L).toList()
        query.run(JournalFilter.CURRENT_MONTH, today = LocalDate.of(2006, 2, 1)) shouldBe emptyList()
    }

    @Test
    fun `rows carry the computed columns of the reduction currency`() {
        val row = query.run(JournalFilter.NONE, reductionCurrencyId = RUB).first { it.operation.id == 105L }
        row.values.convertedAmount shouldBe BigDecimal("28782.5000")
        val inUsd = query.run(JournalFilter.NONE, reductionCurrencyId = USD).first { it.operation.id == 105L }
        inUsd.values.convertedAmount shouldBe BigDecimal("1000")
    }

    @Test
    fun `date conditions`() {
        ids(DateCondition(listOf(DateInterval.day(jan26)))) shouldBe range(100, 101, 102, 103)
        // «Интервалы»: any of several.
        ids(DateCondition(listOf(DateInterval.day(jan1), DateInterval.day(jan6)))) shouldBe range(104, 105, 106, 107, 108, 109)
        // Open-ended, relative upper bound.
        ids(DateCondition(listOf(DateInterval(DateBound.Fixed(jan6), DateBound.Relative(DateAnchor.Today))))) shouldBe
            range(104)
        ids(DateCondition(listOf(DateInterval(null, DateBound.Fixed(jan1))))) shouldBe range(105, 106, 107, 108, 109)
        ids(DateCondition(listOf(DateInterval.month(2006, 1)))) shouldBe (100L..109L).toList()
        ids(DateCondition(listOf(DateInterval.quarter(2006, 2)))) shouldBe emptyList()
        ids(DateCondition(listOf(DateInterval.CURRENT_QUARTER))) shouldBe (100L..109L).toList()
        ids(DateCondition(listOf(DateInterval.TODAY))) shouldBe emptyList()
        shouldThrow<IllegalArgumentException> { DateInterval.quarter(2006, 5) }
    }

    @Test
    fun `relative anchors`() {
        val wednesday = LocalDate.of(2026, 10, 21)
        DateAnchor.entries.associateWith { it.resolve(wednesday).toString() } shouldBe mapOf(
            DateAnchor.Today to "2026-10-21",
            DateAnchor.WeekStart to "2026-10-19",
            DateAnchor.WeekEnd to "2026-10-25",
            DateAnchor.MonthStart to "2026-10-01",
            DateAnchor.MonthEnd to "2026-10-31",
            DateAnchor.QuarterStart to "2026-10-01",
            DateAnchor.QuarterEnd to "2026-12-31",
            DateAnchor.YearStart to "2026-01-01",
            DateAnchor.YearEnd to "2026-12-31",
        )
        DateAnchor.QuarterEnd.resolve(LocalDate.of(2024, 2, 10)) shouldBe LocalDate.of(2024, 3, 31)
    }

    @Test
    fun `reference conditions`() {
        ids(RefCondition(RefField.OperationType, setOf(TRANSFER))) shouldBe range(100, 103, 104)
        ids(RefCondition(RefField.Account, setOf(HUSBAND))) shouldBe range(100, 101, 102, 103, 104, 107)
        ids(RefCondition(RefField.AccountTo, setOf(CHILD))) shouldBe range(104)
        // Inverted: everything but dollars.
        ids(RefCondition(RefField.Currency, setOf(USD), inverted = true)) shouldBe range(102, 103, 104, 106, 107, 108, 109)
        // «Показывать подуровни»: the income root has no operations of its own.
        ids(RefCondition(RefField.BudgetItem, setOf(ROOT_INCOME))) shouldBe emptyList()
        ids(RefCondition(RefField.BudgetItem, setOf(ROOT_INCOME), withDescendants = true)) shouldBe
            range(102, 105, 106, 107, 108, 109)
        shouldThrow<IllegalArgumentException> { RefCondition(RefField.Currency, setOf(USD), withDescendants = true) }
    }

    @Test
    fun `empty and inverted flags`() {
        ids(RefCondition(RefField.AccountFrom, setOf(MAIN))) shouldBe range(100)
        ids(RefCondition(RefField.AccountFrom, setOf(MAIN), includeEmpty = true)) shouldBe range(100, 102, 105, 106, 107, 108, 109)
        ids(RefCondition(RefField.Counterparty, emptySet(), includeEmpty = true)) shouldBe range(100, 103, 104, 105, 106, 107, 108, 109)
        // Inversion applies to empty rows too: they did not pass, so now they do.
        ids(RefCondition(RefField.Counterparty, setOf(EXCHANGERS), inverted = true)) shouldBe
            range(100, 103, 104, 105, 106, 107, 108, 109)
        ids(RefCondition(RefField.Counterparty, setOf(EXCHANGERS), includeEmpty = true, inverted = true)) shouldBe emptyList()
        // «Счёт» is empty only when both accounts are.
        val noAccounts = Operation(1, jan1, BigDecimal.ONE, RUB, null, TRANSFER)
        val q = JournalQuery(Dataset(operations = listOf(noAccounts)), RateService(emptyList(), RUB))
        q.run(JournalFilter(listOf(RefCondition(RefField.Account, setOf(MAIN), includeEmpty = true)))).size shouldBe 1
        q.run(JournalFilter(listOf(RefCondition(RefField.Account, setOf(MAIN))))).size shouldBe 0
    }

    @Test
    fun `amount and text conditions`() {
        ids(AmountCondition(BigDecimal("1000"), BigDecimal("2860"))) shouldBe range(102, 103, 105, 107, 108)
        ids(AmountCondition(BigDecimal("5000"), null)) shouldBe range(106, 109)
        ids(AmountCondition(null, BigDecimal("100"))) shouldBe range(100, 101)
        ids(TextCondition(TextField.Comment, "МЕЛКИЕ")) shouldBe range(104)
        ids(TextCondition(TextField.Comment, "мелкие", inverted = true)) shouldBe range(100, 101, 102, 103, 105, 106, 107, 108, 109)
        ids(TextCondition(TextField.Comment, "zzz", includeEmpty = true)) shouldBe range(100, 101, 102, 103, 105, 106, 107, 108, 109)
    }

    @Test
    fun `conditions combine with and`() {
        ids(RefCondition(RefField.Account, setOf(HUSBAND)), RefCondition(RefField.Currency, setOf(RUB))) shouldBe
            range(102, 103, 104, 107)
    }

    @Test
    fun `sort by signed and converted amounts keeps id order on ties`() {
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.Amount, descending = true))) shouldBe
            range(109, 106, 102, 103, 108, 105, 107, 104, 100, 101)
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.ConvertedAmount, descending = true))) shouldBe
            range(109, 105, 106, 102, 103, 100, 108, 107, 104, 101)
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.Rate), SortKey(JournalColumn.Amount))) shouldBe
            range(104, 107, 108, 102, 103, 106, 109, 101, 100, 105)
    }

    @Test
    fun `multi-sort with reference columns by name, empty first`() {
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.Date), SortKey(JournalColumn.AccountFrom))) shouldBe
            range(105, 106, 107, 108, 109, 104, 102, 101, 103, 100)
        // Жена < Муж < Основной < Ребенок < Счет в банке.
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.AccountTo))) shouldBe
            range(101, 108, 100, 102, 107, 103, 105, 106, 104, 109)
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.Comment, descending = true))).first() shouldBe 104L
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.OperationType))) shouldBe
            range(100, 103, 104, 102, 105, 106, 107, 108, 109, 101)
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.Counterparty, descending = true), SortKey(JournalColumn.Currency))) shouldBe
            range(101, 102, 100, 105, 103, 104, 106, 107, 108, 109)
        ids(JournalFilter.NONE, listOf(SortKey(JournalColumn.BudgetItem))) shouldBe
            range(105, 106, 107, 108, 109, 101, 102, 100, 103, 104)
    }

    @Test
    fun `names sort in Russian collation, not by code point`() {
        val names = listOf("яблоко", "Ель", "ёлка", "Банк")
        val accounts = names.mapIndexed { i, n -> Account(i + 1L, n) }
        val ops = accounts.map { Operation(it.id, jan1, BigDecimal.ONE, RUB, null, null, accountTo = it.id) }
        val q = JournalQuery(Dataset(operations = ops, accounts = accounts), RateService(emptyList(), RUB))
        q.run(JournalFilter.NONE, listOf(SortKey(JournalColumn.AccountTo))).map { accounts[it.operation.id.toInt() - 1].name } shouldBe
            listOf("Банк", "ёлка", "Ель", "яблоко")
    }

    @Test
    fun `empty dates sort first ascending and last descending`() {
        val ops = listOf(
            Operation(1, jan6, BigDecimal.ONE, RUB, null, null),
            Operation(2, null, BigDecimal.ONE, RUB, null, null),
            Operation(3, jan1, BigDecimal.ONE, RUB, null, null),
        )
        val q = JournalQuery(Dataset(operations = ops), RateService(emptyList(), RUB))
        q.run(JournalFilter.NONE, listOf(SortKey(JournalColumn.Date))).map { it.operation.id } shouldBe listOf(2L, 3L, 1L)
        q.run(JournalFilter.NONE, listOf(SortKey(JournalColumn.Date, true))).map { it.operation.id } shouldBe listOf(1L, 3L, 2L)
        // An empty date passes a date condition only with «Пустые».
        q.run(JournalFilter(listOf(DateCondition(listOf(DateInterval(null, null)), includeEmpty = true)))).size shouldBe 3
        q.run(JournalFilter(listOf(DateCondition(listOf(DateInterval(null, null)))))).size shouldBe 2
    }
}
