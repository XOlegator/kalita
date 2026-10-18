// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kalita.storage.api.DbSnapshot
import kalita.storage.api.Table
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SnapshotDiffTest {
    private fun snapshot(vararg rows: Pair<Table, Map<String, Any?>>) =
        DbSnapshot(rows.groupBy({ it.first }, { it.second }))

    @Test
    fun `identical snapshots have no differences`() {
        val s = snapshot(Table.Accounts to mapOf("id" to 1L, "Name" to "Cash"))
        SnapshotDiff.diff(s, s) shouldBe emptyList()
        SnapshotDiff.diff(DbSnapshot.EMPTY, DbSnapshot.EMPTY) shouldBe emptyList()
    }

    @Test
    fun `added, removed and changed rows are reported per table and id`() {
        val before = snapshot(
            Table.Accounts to mapOf("id" to 1L, "Name" to "Cash", "del" to false),
            Table.Accounts to mapOf("id" to 2L, "Name" to "Old", "del" to false),
        )
        val after = snapshot(
            Table.Accounts to mapOf("id" to 1L, "Name" to "Wallet", "del" to true),
            Table.People to mapOf("id" to 1L, "Name" to "Ann"),
        )
        SnapshotDiff.diff(before, after) shouldContainExactlyInAnyOrder listOf(
            RowDiff.Changed(Table.Accounts, 1, mapOf("Name" to ("Cash" to "Wallet"), "del" to (false to true))),
            RowDiff.Removed(Table.Accounts, 2, mapOf("id" to 2L, "Name" to "Old", "del" to false)),
            RowDiff.Added(Table.People, 1, mapOf("id" to 1L, "Name" to "Ann")),
        )
    }

    @Test
    fun `currency values compare by amount, not by scale`() {
        val before = snapshot(Table.Operations to mapOf("id" to 1L, "Summa" to BigDecimal("350.5")))
        val same = snapshot(Table.Operations to mapOf("id" to 1L, "Summa" to BigDecimal("350.5000")))
        val other = snapshot(Table.Operations to mapOf("id" to 1L, "Summa" to BigDecimal("350.51")))
        SnapshotDiff.diff(before, same) shouldBe emptyList()
        SnapshotDiff.diff(before, other).single() shouldBe
            RowDiff.Changed(Table.Operations, 1, mapOf("Summa" to (BigDecimal("350.5") to BigDecimal("350.51"))))
    }

    @Test
    fun `a column present on one side only counts as changed`() {
        val before = snapshot(Table.People to mapOf("id" to 1L))
        val after = snapshot(Table.People to mapOf("id" to 1L, "Name" to "Ann"))
        SnapshotDiff.diff(before, after).single() shouldBe RowDiff.Changed(Table.People, 1, mapOf("Name" to (null to "Ann")))
    }
}
