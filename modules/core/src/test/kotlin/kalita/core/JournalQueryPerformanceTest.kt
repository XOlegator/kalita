// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.random.Random

/**
 * dev_plan.md NFR-3 / stage 2 acceptance: on a journal of 100 000 operations, filtering and sorting
 * take ≤ 200 ms. Measured as the best of 10 runs after JIT warm-up — the budget is for a
 * steady-state desktop, and the best run is the least sensitive to a busy CI machine (`build` runs
 * these tests while other modules compile and lint in parallel).
 */
class JournalQueryPerformanceTest {
    private val random = Random(20061026)
    private val start: LocalDate = LocalDate.of(2016, 1, 1)
    private val today: LocalDate = LocalDate.of(2026, 10, 21)

    private val currencies = listOf(Currency(1, "Рубль", null), Currency(2, "Доллар", "R01235"), Currency(3, "Евро", "R01239"))
    private val types = listOf(OperationType(1, "Приход", 1, 1), OperationType(2, "Расход", 2, -1), OperationType(3, "Перемещение", 3, 0))
    private val accounts = (1L..12L).map { Account(it, "Счёт $it") }
    private val people = (1L..300L).map { Counterparty(it, "Магазин ${random.nextInt(1000)}") }

    // Three type roots, 20 groups each, 5 items under every group.
    private val items = buildList {
        for (root in 1L..3L) add(BudgetItem(root, null, types[root.toInt() - 1].name, root))
        var id = 100L
        for (root in 1L..3L) {
            repeat(20) { g ->
                val group = id++
                add(BudgetItem(group, root, "Группа $root.$g", g.toLong()))
                repeat(5) { add(BudgetItem(id++, group, "Статья ${random.nextInt(10_000)}", it.toLong())) }
            }
        }
    }
    private val leaves = items.filter { item -> items.none { it.parentId == item.id } }

    // Two quoted currencies, one rate every ~5 days over the whole period.
    private val rates = (2L..3L).flatMap { currency ->
        (0 until 800).map { i -> Rate(currency * 10_000 + i, currency, start.plusDays(i * 5L), 60.0 + random.nextDouble(40.0)) }
    }

    private val operations = (1L..100_000L).map { id ->
        val type = types[random.nextInt(types.size)]
        Operation(
            id = id,
            date = start.plusDays(random.nextLong(3_950)),
            amount = BigDecimal.valueOf(random.nextLong(1, 10_000_000), 2),
            currencyId = currencies[random.nextInt(currencies.size)].id,
            budgetItemId = leaves[random.nextInt(leaves.size)].id,
            typeId = type.id,
            comment = if (random.nextInt(3) == 0) "Покупка №${random.nextInt(100_000)}" else null,
            counterpartyId = people.takeIf { random.nextBoolean() }?.random(random)?.id,
            accountFrom = accounts.random(random).id.takeIf { type.kind != OperationKind.Income },
            accountTo = accounts.random(random).id.takeIf { type.kind != OperationKind.Expense },
            deleted = random.nextInt(50) == 0,
        )
    }

    private val dataset = Dataset(operations, types, items, accounts, people, currencies, rates)
    private val query = JournalQuery(dataset, RateService.of(dataset))

    private fun assertFast(case: String, block: () -> List<JournalRow>) {
        fun millis(): Long {
            val t0 = System.nanoTime()
            block().shouldNotBeEmpty()
            return (System.nanoTime() - t0) / 1_000_000
        }
        val first = millis()
        repeat(9) { millis() }
        val best = (1..10).minOf { millis() }
        val summary = "JournalQuery, $case, ${operations.size} operations: first $first ms, best $best ms"
        println(summary)
        withClue(summary) { best shouldBeLessThanOrEqual 200 }
    }

    @Test
    fun `whole journal sorted by date and converted amount`() {
        val sort = listOf(SortKey(JournalColumn.Date, descending = true), SortKey(JournalColumn.ConvertedAmount))
        assertFast("whole journal") { query.run(JournalFilter.NONE, sort, reductionCurrencyId = 1, today = today) }
    }

    @Test
    fun `filter panel conditions with a sort by reference names`() {
        val filter = JournalFilter(
            listOf(
                DateCondition(listOf(DateInterval(DateBound.Fixed(start.plusYears(2)), DateBound.Relative(DateAnchor.Today)))),
                RefCondition(RefField.BudgetItem, setOf(2L, 3L), withDescendants = true),
                RefCondition(RefField.Account, setOf(1L, 2L, 3L, 4L, 5L, 6L), includeEmpty = true),
                AmountCondition(BigDecimal("10.00"), null),
            ),
        )
        val sort = listOf(SortKey(JournalColumn.BudgetItem), SortKey(JournalColumn.Counterparty), SortKey(JournalColumn.Amount, true))
        assertFast("filter panel") { query.run(filter, sort, reductionCurrencyId = 2, today = today) }
    }

    @Test
    fun `substring filter with a sort by comment`() {
        val filter = JournalFilter(listOf(TextCondition(TextField.Comment, "№1", inverted = true)))
        assertFast("comment") { query.run(filter, listOf(SortKey(JournalColumn.Comment)), today = today) }
    }
}
