// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

class DuplicateSearchTest {
    private val date = LocalDate.of(2026, 11, 28)
    private val draft = OperationDraft(date, BigDecimal("100.00"), 1, typeId = 2, accountFrom = 3, accountTo = 4)

    private fun op(id: Long, amount: String = "100") = Operation(
        id, date, BigDecimal(amount), 1, null, 2, accountFrom = 3, accountTo = 4,
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
        result.groupsTruncated shouldBe false
    }

    @Test
    fun `groups return original rows and per-row account differences`() {
        val a = op(1, "20")
        val b = op(2, "30").copy(accountFrom = 7)
        val c = op(3, "50.00").copy(accountTo = 8)
        val result = DuplicateSearch.find(draft, listOf(a, b, c))
        result.matches shouldBe listOf(
            DuplicateMatch(listOf(DuplicateOperation(a, false, false), DuplicateOperation(b, true, false), DuplicateOperation(c, false, true))),
        )
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
    fun `group search is bounded without limiting singles`() {
        val rows = (1L..21L).map { op(it, "1") } + op(22)
        val result = DuplicateSearch.find(draft, rows)
        result.groupsTruncated shouldBe true
        result.matches shouldBe listOf(DuplicateMatch(listOf(DuplicateOperation(rows.last(), false, false))))
        DuplicateSearch.find(draft, rows.take(20)).groupsTruncated shouldBe false
    }
}