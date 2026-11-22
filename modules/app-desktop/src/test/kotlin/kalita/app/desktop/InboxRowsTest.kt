// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.Locale

class InboxRowsTest {
    @Test
    fun `agent preview preserves identifiers and displays optional fields`() {
        val result = Json.parseToJsonElement(
            """{"entries":[{"id":"draft-1","createdAt":"2026-01-01T00:00:00Z","device":"agent:test","clientRequestId":"req-1","preview":{"date":"2026-01-02","amount":"10.00","currency":"RUB","operationType":"Expense","budgetItem":"Food","counterparty":null,"accountFrom":"Card","accountTo":null,"comment":"Lunch"}}]}""",
        ).jsonObject
        agentInboxRows(result, Locale.US, ZoneId.of("America/New_York")) shouldBe listOf(
            listOf("Dec 31, 2025, 7:00:00\u202fPM", "Jan 2, 2026", "10.00", "RUB", "Expense", "Food", "", "Card", "", "Lunch", "agent:test", "req-1", "draft-1"),
        )
    }

    @Test
    fun `invalid and missing dates remain visible without breaking the inbox`() {
        val result = Json.parseToJsonElement(
            """{"entries":[{"id":"id","createdAt":"invalid","device":"agent","clientRequestId":"req","preview":{"date":"bad-date"}},{"id":"id2","preview":{}}]}""",
        ).jsonObject
        val rows = agentInboxRows(result, Locale.US, ZoneId.of("UTC"))
        rows[0].take(2) shouldBe listOf("invalid", "bad-date")
        rows[1].take(2) shouldBe listOf("", "")
    }

    @Test
    fun `empty inbox has no rows`() {
        agentInboxRows(Json.parseToJsonElement("""{"entries":[]}""").jsonObject) shouldBe emptyList()
    }
}