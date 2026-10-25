// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import io.kotest.matchers.shouldBe
import kalita.storage.api.Conflict
import kalita.storage.api.SubmitResult
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.UUID

/** Every [SubmitResult] variant must give back an equal value after [SubmitResultJson.encode] then
 * [SubmitResultJson.decode] — the wire format for [AgentChannelServer]'s responses. */
class SubmitResultJsonTest {
    private val changeSetId = UUID.randomUUID()

    private fun roundTrip(result: SubmitResult): SubmitResult =
        SubmitResultJson.decode(Json.parseToJsonElement(SubmitResultJson.encode(result).toString()).jsonObject)

    @Test
    fun `Applied round-trips its temp keys`() {
        val result = SubmitResult.Applied(mapOf(TempKey("a") to 1L, TempKey("b") to 2L))
        roundTrip(result) shouldBe result
    }

    @Test
    fun `Failed round-trips its reason`() {
        val result = SubmitResult.Failed("read-only: another Kalita instance has the database open")
        roundTrip(result) shouldBe result
    }

    @Test
    fun `Conflicts round-trips every conflict kind`() {
        val result = SubmitResult.Conflicts(
            listOf(
                Conflict.RowMissing(changeSetId, Table.Accounts, 1),
                Conflict.FieldChanged(changeSetId, Table.Operations, 2, "Comment", "old", "new"),
                Conflict.FieldChanged(changeSetId, Table.Operations, 3, "Summa", null, java.math.BigDecimal("12.50")),
                Conflict.UnresolvedReference(changeSetId, Table.BudgetItems, TempKey("x")),
                Conflict.UnknownColumn(changeSetId, Table.Currencies, "NoSuchColumn"),
            ),
        )
        roundTrip(result) shouldBe result
    }
}
