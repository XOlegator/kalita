// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kalita.core.JournalColumn
import kalita.core.JournalFilter
import org.junit.jupiter.api.Test

class JournalRowFormatterTest {
    private val dataset = DemoPresentationData.dataset
    private val row = JournalQueryFixture.rows(dataset).first { it.operation.id == 100L }

    @Test
    fun `cellText formats the account and date columns`() {
        JournalRowFormatter.cellText(dataset, row, JournalColumn.AccountFrom) shouldBe "Основной"
        JournalRowFormatter.cellText(dataset, row, JournalColumn.AccountTo) shouldBe "Муж"
        JournalRowFormatter.cellText(dataset, row, JournalColumn.Date) shouldBe "26.01.2006"
    }

    @Test
    fun `allText contains every column's cellText`() {
        val all = JournalRowFormatter.allText(dataset, row)
        all shouldContain "Основной"
        all shouldContain "Муж"
        all shouldContain "26.01.2006"
    }
}

private object JournalQueryFixture {
    fun rows(dataset: kalita.core.Dataset) =
        kalita.core.JournalQuery(dataset, kalita.core.RateService.of(dataset)).run(JournalFilter.NONE)
}
