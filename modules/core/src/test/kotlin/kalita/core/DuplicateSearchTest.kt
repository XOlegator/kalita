// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

class DuplicateSearchTest {
    private val date = LocalDate.of(2026, 11, 28)
    private val draft = OperationDraft(date, BigDecimal("100.00"), 1, typeId = 2, accountFrom = 3, accountTo = 4)

    private fun op(id: Long, amount: String = "100", day: LocalDate = date) = Operation(
        id, day, BigDecimal(amount), 1, null, 2, accountFrom = 3, accountTo = 4,
    )

    @Test
    fun `singles compare numeric amounts and report both account differences`() {
        val same = op(1).copy(date = date.minusDays(3))
        val different = op(2).copy(date = date.plusDays(3), accountFrom = 9, accountTo = null)
        val result = DuplicateSearch.find(draft, listOf(same, different))
        result.matches shouldBe listOf(
            DuplicateMatch(listOf(DuplicateOperation(same, false, false))),
            DuplicateMatch(listOf(DuplicateOperation(different, true, true))),
        )
        result.truncated.shouldBeFalse()
    }

    @Test
    fun `groups of any size sum within one day and report the difference`() {
        val a = op(1, "20")
        val b = op(2, "30").copy(accountFrom = 7)
        val c = op(3, "50.00").copy(accountTo = 8)
        val d = op(4, "0.01", date.plusDays(1))
        val e = op(5, "99.99", date.plusDays(1))
        val result = DuplicateSearch.find(draft, listOf(a, b, c, d, e))
        val three = result.matches.first { it.operations.size == 3 }
        three.operations.map { it.operation.id } shouldBe listOf(1L, 2L, 3L)
        three.amountDifference shouldBe BigDecimal.ZERO
        val pair = result.matches.first { it.operations.size == 2 }
        pair.operations.map { it.operation.id } shouldBe listOf(4L, 5L)
        pair.amountDifference shouldBe BigDecimal.ZERO
        result.matches shouldHaveSize 2
    }

    @Test
    fun `groups never span two days`() {
        val a = op(1, "40", date.minusDays(1))
        val b = op(2, "60", date)
        DuplicateSearch.find(draft, listOf(a, b)).matches.shouldBeEmpty()
    }

    @Test
    fun `amount tolerance widens singles and groups`() {
        val result = DuplicateSearch.find(draft, listOf(op(1, "99.50"), op(2, "40"), op(3, "59.50")), amountTolerance = BigDecimal("1"))
        result.matches shouldHaveSize 2
        // Best first: the nearer single, then the group; both half a unit off.
        result.matches[0].operations.map { it.operation.id } shouldBe listOf(1L)
        result.matches[0].amountDifference shouldBe BigDecimal("-0.5")
        result.matches[1].operations.map { it.operation.id } shouldBe listOf(2L, 3L)
        result.matches[1].amountDifference shouldBe BigDecimal("-0.5")
    }

    @Test
    fun `tolerance is capped and must not be negative`() {
        assertThrows<IllegalArgumentException> { DuplicateSearch.find(draft, emptyList(), amountTolerance = BigDecimal("-0.01")) }
        assertThrows<IllegalArgumentException> { DuplicateSearch.find(draft, emptyList(), amountTolerance = BigDecimal("100.01")) }
        DuplicateSearch.find(draft, listOf(op(1, "200")), amountTolerance = DuplicateSearch.MAX_AMOUNT_TOLERANCE).matches shouldHaveSize 1
    }

    @Test
    fun `kindOf lets another type of the same kind match`() {
        val otherType = op(1).copy(typeId = 7)
        DuplicateSearch.find(draft, listOf(otherType)).matches.shouldBeEmpty()
        val result = DuplicateSearch.find(draft, listOf(otherType), kindOf = { id -> if (id == 2L || id == 7L) OperationKind.Expense else null })
        result.matches shouldHaveSize 1
    }

    @Test
    fun `pairs are found and unrelated rows cannot form groups`() {
        val a = op(1, "40")
        val b = op(2, "60")
        val result = DuplicateSearch.find(draft, listOf(a, b, op(3, "60").copy(typeId = 8)))
        result.matches shouldBe listOf(DuplicateMatch(listOf(DuplicateOperation(a, false, false), DuplicateOperation(b, false, false))))
    }

    @Test
    fun `deleted edited invalid and mismatched rows are excluded`() {
        val rows = listOf(
            op(1), op(2).copy(deleted = true), op(3).copy(date = date.plusDays(4)),
            op(4).copy(date = date.minusDays(4)), op(5).copy(typeId = 3), op(6).copy(currencyId = 2),
            op(7).copy(date = null), op(8).copy(amount = null), op(9, "0"), op(10, "-1"),
            op(11).copy(typeId = null), op(12).copy(currencyId = null),
        )
        DuplicateSearch.find(draft, rows, editingId = 1).matches.shouldBeEmpty()
        DuplicateSearch.find(draft, listOf(op(1).copy(date = date.plusDays(1))), daysWindow = 0).matches.shouldBeEmpty()
    }

    @Test
    fun `incomplete or non-positive drafts do not match`() {
        val drafts = listOf(
            draft.copy(date = null), draft.copy(amount = null), draft.copy(amount = BigDecimal.ZERO),
            draft.copy(amount = BigDecimal("-1")), draft.copy(typeId = null), draft.copy(currencyId = null),
        )
        drafts.forEach { DuplicateSearch.find(it, listOf(op(1))).matches.shouldBeEmpty() }
        assertThrows<IllegalArgumentException> { DuplicateSearch.find(draft, emptyList(), daysWindow = -1) }
    }

    @Test
    fun `search is bounded and reports what it could not look at`() {
        val small = (1L..30L).map { op(it, "1") }
        // More rows of one day than candidatesPerDay allows: some rows were left out, singles still work.
        val candidateTruncated = DuplicateSearch.find(draft, small + op(31), limits = DuplicateLimits(candidatesPerDay = 5, maxVisitsPerDay = 10_000))
        candidateTruncated.truncated.shouldBeTrue()
        candidateTruncated.matches.map { it.operations.size } shouldBe listOf(1)
        // Enough rows that could pair up to the target exhausts the visit budget.
        val pairs = (1L..12L).map { op(it, "40") } + (13L..24L).map { op(it, "60") }
        val visitsTruncated = DuplicateSearch.find(draft, pairs, limits = DuplicateLimits(candidatesPerDay = 24, maxVisitsPerDay = 10, maxMatches = 5))
        visitsTruncated.truncated.shouldBeTrue()
        DuplicateSearch.find(draft, pairs, limits = DuplicateLimits(candidatesPerDay = 24, maxVisitsPerDay = 10_000, maxMatches = 200)).truncated.shouldBeFalse()
    }

    @Test
    fun `matches are ordered best first regardless of input order`() {
        val exact = op(1, "100", date.plusDays(2))
        val near = op(2, "100.5")
        val group = listOf(op(3, "40"), op(4, "60.5"))
        val result = DuplicateSearch.find(draft, listOf(group[1], group[0], exact, near), amountTolerance = BigDecimal("1"))
        result.matches.map { it.amountDifference.abs() }.zipWithNext().forEach { (a, b) -> (a <= b).shouldBeTrue() }
        result.matches.first().operations.single().operation.id shouldBe 1L
    }
}
