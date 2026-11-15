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
    fun `the audit user is the one the existing rows carry most often`() {
        val journal = listOf(mapOf("id" to 1L, "Editor" to 3L), mapOf("id" to 2L, "Editor" to 3), mapOf("id" to 3L, "Editor" to 1L), mapOf("id" to 4L, "Editor" to null, "Creator" to 3L))
        DbSnapshot(mapOf(Table.Operations to journal)).auditUserId shouldBe 3
        // No stamped operations: the dictionaries decide.
        DbSnapshot(mapOf(Table.Operations to listOf(mapOf("id" to 1L)), Table.Accounts to listOf(mapOf("id" to 5L, "Creator" to 7L)))).auditUserId shouldBe 7
        DbSnapshot.EMPTY.auditUserId shouldBe null
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
