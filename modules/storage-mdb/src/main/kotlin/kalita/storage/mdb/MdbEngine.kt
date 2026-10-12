// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.github.spannm.jackcess.CursorBuilder
import io.github.spannm.jackcess.Database
import io.github.spannm.jackcess.DatabaseBuilder
import io.github.spannm.jackcess.IndexCursor
import kalita.storage.api.ApplyResult
import kalita.storage.api.ChangeSet
import kalita.storage.api.Conflict
import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Applies [ChangeSet]s to a `Data.mdb` file through Jackcess (dev_plan.md §9.5).
 *
 * This is the in-place step only: callers hand it a *scratch copy* of the database and replace
 * the working file after verification (§9.6 atomic commit). Change sets are applied in order and
 * application stops at the first change set that conflicts; rows of that change set written
 * before the conflict was detected are not rolled back — the scratch copy must be discarded
 * whenever [ApplyResult.ok] is false.
 */
object MdbEngine {
    fun apply(file: File, changeSets: List<ChangeSet>): ApplyResult {
        // Charset is deliberately left at the default (dev_plan.md §3.2 item 6); sync is manual.
        DatabaseBuilder(file).withAutoSync(false).open().use { db ->
            val check = SchemaFingerprint.check(db)
            require(check is SchemaCheck.Compatible) { "Schema is not writable: $check" }
            val result = Applier(db).apply(changeSets)
            db.flush()
            return result
        }
    }

    private class Applier(private val db: Database) {
        private val tempKeys = mutableMapOf<TempKey, Long>()
        private val conflicts = mutableListOf<Conflict>()
        private val columnNames = XCASH_SCHEMA.associate { it.table to it.columns.map { c -> c.first }.toSet() }

        fun apply(changeSets: List<ChangeSet>): ApplyResult {
            for (cs in changeSets) {
                for (op in cs.ops) applyOp(cs.id, op)
                if (conflicts.isNotEmpty()) break
            }
            return ApplyResult(tempKeys.toMap(), conflicts.toList())
        }

        private fun applyOp(csId: UUID, op: Op) {
            if (conflicts.isNotEmpty()) return
            when (op) {
                is Op.Insert -> insert(csId, op)
                is Op.Update -> withRow(csId, op.table, op.row) { cursor, id ->
                    val current = cursor.currentRow
                    val newValues = mutableMapOf<String, Any?>()
                    for ((column, change) in op.fields) {
                        if (unknown(csId, op.table, column)) return@withRow
                        if (!sameValue(current[column], change.base)) {
                            conflicts += Conflict.FieldChanged(csId, op.table, id, column, change.base, current[column])
                            return@withRow
                        }
                        val resolved = resolve(csId, op.table, change.new)
                        if (resolved === UNRESOLVED) return@withRow
                        newValues[column] = resolved
                    }
                    cursor.updateCurrentRowFromMap(newValues.mapValues { (_, v) -> normalize(v) })
                }
                is Op.SetDeleted -> withRow(csId, op.table, op.row) { cursor, id ->
                    val actual = cursor.currentRow["del"] == true
                    if (actual != op.base) {
                        conflicts += Conflict.FieldChanged(csId, op.table, id, "del", op.base, actual)
                        return@withRow
                    }
                    cursor.updateCurrentRowFromMap(linkedMapOf<String, Any?>("del" to op.new))
                }
                is Op.HardDelete -> withRow(csId, op.table, op.row) { cursor, id ->
                    val current = cursor.currentRow
                    for ((column, base) in op.baseRow) {
                        if (!sameValue(current[column], base)) {
                            conflicts += Conflict.FieldChanged(csId, op.table, id, column, base, current[column])
                            return@withRow
                        }
                    }
                    cursor.deleteCurrentRow()
                }
            }
        }

        private fun insert(csId: UUID, op: Op.Insert) {
            val values = mutableMapOf<String, Any?>()
            for ((column, raw) in op.values) {
                if (unknown(csId, op.table, column)) return
                if (column == "id") continue
                val resolved = resolve(csId, op.table, raw)
                if (resolved === UNRESOLVED) return
                values[column] = normalize(resolved)
            }
            val row = db.getTable(op.table.tableName).addRowFromMap(LinkedHashMap(values))
            tempKeys[op.tempKey] = (row["id"] as Number).toLong()
        }

        /** Locates the target row by primary key and runs [block] with the cursor positioned on it. */
        private inline fun withRow(csId: UUID, table: Table, ref: RowRef, block: (IndexCursor, Long) -> Unit) {
            val id = when (ref) {
                is RowRef.Existing -> ref.id
                is RowRef.New -> tempKeys[ref.tempKey] ?: run {
                    conflicts += Conflict.UnresolvedReference(csId, table, ref.tempKey)
                    return
                }
            }
            val t = db.getTable(table.tableName)
            val cursor = CursorBuilder.createPrimaryKeyCursor(t)
            if (!cursor.findFirstRow(mapOf("id" to id))) {
                conflicts += Conflict.RowMissing(csId, table, id)
                return
            }
            block(cursor, id)
        }

        private fun unknown(csId: UUID, table: Table, column: String): Boolean {
            if (column in columnNames.getValue(table)) return false
            conflicts += Conflict.UnknownColumn(csId, table, column)
            return true
        }

        /** Replaces a [TempKey] value with the inserted row's id; [UNRESOLVED] (and a conflict) if not yet inserted. */
        private fun resolve(csId: UUID, table: Table, value: Any?): Any? {
            if (value !is TempKey) return value
            return tempKeys[value] ?: run {
                conflicts += Conflict.UnresolvedReference(csId, table, value)
                UNRESOLVED
            }
        }
    }
}

private val UNRESOLVED = Any()

/** Jet stores dates to the second (as the original does) and Currency as scale-4 decimals. */
private fun normalize(value: Any?): Any? = when (value) {
    is LocalDateTime -> value.truncatedTo(ChronoUnit.SECONDS)
    is Int -> value.toLong()
    else -> value
}

private fun sameValue(actual: Any?, base: Any?): Boolean {
    val a = normalize(actual)
    val b = normalize(base)
    return when {
        a is BigDecimal && b is BigDecimal -> a.compareTo(b) == 0
        a is Number && b is Number && a !is BigDecimal && b !is BigDecimal -> a.toDouble() == b.toDouble()
        else -> a == b
    }
}
