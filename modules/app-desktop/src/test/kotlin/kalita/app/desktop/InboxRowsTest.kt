// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class InboxRowsTest {
    @Test
    fun `agent preview preserves identifiers and displays optional fields`() {
        val result = Json.parseToJsonElement(
            """{"entries":[{"id":"draft-1","createdAt":"2026-01-01T00:00:00Z","device":"agent:test","clientRequestId":"req-1","preview":{"date":"2026-01-02","amount":"10.00","currency":"RUB","operationType":"Expense","budgetItem":"Food","counterparty":null,"accountFrom":"Card","accountTo":null,"comment":"Lunch"}}]}""",
        ).jsonObject
        agentInboxRows(result) shouldBe listOf(
            listOf("2026-01-01T00:00:00Z", "agent:test", "req-1", "draft-1", "2026-01-02", "10.00", "RUB", "Expense", "Food", "", "Card", "", "Lunch"),
        )
    }

    @Test
    fun `empty inbox has no rows`() {
        agentInboxRows(Json.parseToJsonElement("""{"entries":[]}""").jsonObject) shouldBe emptyList()
    }
}