// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class JournalFilterJsonTest {
    private val everything = JournalFilter(
        listOf(
            DateCondition(
                listOf(
                    DateInterval.CURRENT_MONTH,
                    DateInterval.day(LocalDate.of(2006, 1, 26)),
                    DateInterval(null, DateBound.Relative(DateAnchor.Today)),
                    DateInterval(DateBound.Fixed(LocalDate.of(2006, 1, 1)), null),
                ),
                includeEmpty = true,
            ),
            RefCondition(RefField.BudgetItem, setOf(41, 3, 999_999_999_999), withDescendants = true),
            RefCondition(RefField.Account, setOf(22), inverted = true),
            RefCondition(RefField.Counterparty, emptySet(), includeEmpty = true, inverted = true),
            AmountCondition(BigDecimal("0.01"), BigDecimal("922337203685477.5807")),
            AmountCondition(null, BigDecimal("100")),
            TextCondition(TextField.Comment, "Мелкие \"расходы\"\n"),
        ),
    )

    @Test
    fun `every condition kind survives a round trip`() {
        JournalFilterJson.decodeFromString(JournalFilterJson.encodeToString(everything)) shouldBe everything
        JournalFilterJson.decode(JournalFilterJson.encode(JournalFilter.NONE)) shouldBe JournalFilter.NONE
    }

    @Test
    fun `the stored form is stable and compact`() {
        JournalFilterJson.encodeToString(JournalFilter.CURRENT_MONTH) shouldBe
            """{"version":1,"conditions":[{"kind":"date","intervals":[{"from":{"relative":"MonthStart"},"to":{"relative":"MonthEnd"}}]}]}"""
        JournalFilterJson.encodeToString(JournalFilter(listOf(RefCondition(RefField.Currency, setOf(3, 1), includeEmpty = true)))) shouldBe
            """{"version":1,"conditions":[{"kind":"ref","field":"Currency","ids":[1,3],"includeEmpty":true}]}"""
    }

    @Test
    fun `sort round trip`() {
        val sort = listOf(SortKey(JournalColumn.Date, descending = true), SortKey(JournalColumn.BudgetItem))
        val json = JournalFilterJson.encodeSort(sort)
        json.toString() shouldBe """[{"column":"Date","descending":true},{"column":"BudgetItem"}]"""
        JournalFilterJson.decodeSort(json) shouldBe sort
    }

    @Test
    fun `unknown or broken input is rejected with IllegalArgumentException`() {
        listOf(
            """{"version":2,"conditions":[]}""",
            """{"conditions":[]}""",
            """{"version":1,"conditions":[{"kind":"regex"}]}""",
            """{"version":1,"conditions":[{"kind":"ref","field":"Nope","ids":[]}]}""",
            """{"version":1,"conditions":[{"kind":"ref","field":"Currency"}]}""",
            """{"version":1,"conditions":[{"kind":"ref","field":"Currency","ids":[],"withDescendants":true}]}""",
            """{"version":1,"conditions":[{"kind":"amount","min":"abc"}]}""",
            """{"version":1,"conditions":[{"kind":"date","intervals":[{"from":{"fixed":"2006-13-01"}}]}]}""",
            """{"version":1,"conditions":[{"kind":"date","intervals":[{"from":{}}]}]}""",
            """{"version":1,"conditions":[{"kind":"text","field":"Comment"}]}""",
            """not json""",
        ).forEach { text -> shouldThrow<IllegalArgumentException> { JournalFilterJson.decodeFromString(text) } }
        shouldThrow<IllegalArgumentException> {
            JournalFilterJson.decodeSort(Json.parseToJsonElement("""[{"column":"Nope"}]""").jsonArray)
        }
        shouldThrow<IllegalArgumentException> {
            JournalFilterJson.decodeSort(Json.parseToJsonElement("""[{}]""").jsonArray)
        }
    }
}
