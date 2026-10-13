// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import kalita.storage.api.DbSnapshot
import kalita.storage.api.Row
import kalita.storage.api.Table
import java.math.BigDecimal

sealed interface RowDiff {
    val table: Table
    val id: Long

    data class Added(override val table: Table, override val id: Long, val row: Row) : RowDiff
    data class Removed(override val table: Table, override val id: Long, val row: Row) : RowDiff

    /** [changes] maps each differing column to (before, after). */
    data class Changed(override val table: Table, override val id: Long, val changes: Map<String, Pair<Any?, Any?>>) : RowDiff
}

/** Row-level difference between two snapshots, keyed by `id` (dev_plan.md stage 1 `cli diff`). */
object SnapshotDiff {
    fun diff(before: DbSnapshot, after: DbSnapshot): List<RowDiff> = buildList {
        for (table in Table.entries) {
            val a = before.rows(table).associateBy { (it["id"] as Number).toLong() }
            val b = after.rows(table).associateBy { (it["id"] as Number).toLong() }
            for ((id, row) in a) {
                val other = b[id]
                if (other == null) {
                    add(RowDiff.Removed(table, id, row))
                    continue
                }
                val changes = (row.keys + other.keys).filter { !same(row[it], other[it]) }
                    .associateWith { row[it] to other[it] }
                if (changes.isNotEmpty()) add(RowDiff.Changed(table, id, changes))
            }
            for ((id, row) in b) if (id !in a) add(RowDiff.Added(table, id, row))
        }
    }

    private fun same(x: Any?, y: Any?): Boolean =
        if (x is BigDecimal && y is BigDecimal) x.compareTo(y) == 0 else x == y
}
