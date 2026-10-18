// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DbSnapshotTest {
    @Test
    fun `rows are looked up by id whatever numeric type the id has`() {
        val snapshot = DbSnapshot(
            mapOf(Table.Accounts to listOf(mapOf("id" to 7L, "Name" to "Cash"), mapOf("id" to 9, "Name" to "Card"))),
        )
        snapshot.row(Table.Accounts, 7)!!["Name"] shouldBe "Cash"
        snapshot.row(Table.Accounts, 9)!!["Name"] shouldBe "Card"
        snapshot.row(Table.Accounts, 8) shouldBe null
        snapshot.rows(Table.People) shouldBe emptyList()
    }

    @Test
    fun `the empty snapshot has no rows in any table`() {
        Table.entries.forEach {
            DbSnapshot.EMPTY.rows(it) shouldBe emptyList()
            DbSnapshot.EMPTY.row(it, 1) shouldBe null
        }
    }

    @Test
    fun `apply result is ok only without conflicts`() {
        ApplyResult(mapOf(TempKey("a") to 1L), emptyList()).ok shouldBe true
        ApplyResult(emptyMap(), listOf(Conflict.UnknownColumn(java.util.UUID.randomUUID(), Table.People, "x"))).ok shouldBe false
    }
}
