// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kalita.core.JournalColumn
import kalita.core.SortKey
import org.junit.jupiter.api.Test

class SortCodecTest {
    @Test
    fun `empty sort encodes to an empty string`() {
        encodeSortCompact(emptyList()) shouldBe ""
        decodeSortCompact("").shouldBeEmpty()
    }

    @Test
    fun `round-trips a single-level sort`() {
        val sort = listOf(SortKey(JournalColumn.Date, descending = true))
        decodeSortCompact(encodeSortCompact(sort)) shouldBe sort
    }

    @Test
    fun `round-trips a multi-level sort preserving order`() {
        val sort = listOf(
            SortKey(JournalColumn.OperationType, descending = false),
            SortKey(JournalColumn.Amount, descending = true),
            SortKey(JournalColumn.Comment, descending = false),
        )
        decodeSortCompact(encodeSortCompact(sort)) shouldBe sort
    }

    @Test
    fun `an unrecognised column name is skipped, not fatal`() {
        decodeSortCompact("NotAColumn:true;Date:false") shouldBe listOf(SortKey(JournalColumn.Date, descending = false))
    }
}
