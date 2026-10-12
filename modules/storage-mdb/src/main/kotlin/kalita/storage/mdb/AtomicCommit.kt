// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.github.spannm.jackcess.CursorBuilder
import io.github.spannm.jackcess.Database
import io.github.spannm.jackcess.DatabaseBuilder
import kalita.storage.api.ApplyResult
import kalita.storage.api.ChangeSet
import kalita.storage.api.Conflict
import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

sealed interface CommitResult {
    /** The working file now contains the changes; [tempKeys] maps new rows to their real ids. */
    data class Committed(val tempKeys: Map<TempKey, Long>) : CommitResult

    /** Nothing was written; the change sets stay queued until the conflicts are resolved. */
    data class Conflicts(val conflicts: List<Conflict>) : CommitResult

    /** Nothing was written (I/O error, failed verification, unknown schema). */
    data class Failed(val reason: String, val cause: Throwable? = null) : CommitResult
}

/**
 * Atomic commit of change sets into the working `Data.mdb` (ADR-0002, dev_plan.md §9.6):
 * copy → apply on the copy → reopen and verify → fsync → atomic replace. Any failure removes the
 * copy and leaves the working file untouched.
 */
object AtomicCommit {
    fun commit(workingFile: File, changeSets: List<ChangeSet>): CommitResult {
        val tmp = File(workingFile.parentFile, workingFile.name + ".kalita-tmp")
        try {
            val before = rowCounts(workingFile)
            Files.copy(workingFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)

            val applied: ApplyResult = MdbEngine.apply(tmp, changeSets)
            if (!applied.ok) return CommitResult.Conflicts(applied.conflicts)

            verify(tmp, before, changeSets, applied)?.let { return CommitResult.Failed("verification failed: $it") }

            fsync(tmp)
            Files.move(tmp.toPath(), workingFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            fsyncDirectory(workingFile.absoluteFile.parentFile)
            return CommitResult.Committed(applied.tempKeys)
        } catch (e: Exception) {
            return CommitResult.Failed(e.message ?: e.javaClass.simpleName, e)
        } finally {
            tmp.delete()
        }
    }

    private fun rowCounts(file: File): Map<Table, Int> =
        DatabaseBuilder.open(file).use { db -> Table.entries.associateWith { db.getTable(it.tableName).rowCount } }

    /** Returns null when the copy is consistent, otherwise a description of the first problem. */
    private fun verify(file: File, before: Map<Table, Int>, changeSets: List<ChangeSet>, applied: ApplyResult): String? =
        DatabaseBuilder.open(file).use { db ->
            (SchemaFingerprint.check(db) as? SchemaCheck.Mismatch)?.let { return "schema changed: ${it.problems}" }

            val ops = changeSets.flatMap { it.ops }
            for (table in Table.entries) {
                val expected = before.getValue(table) +
                    ops.count { it is Op.Insert && it.table == table } -
                    ops.count { it is Op.HardDelete && it.table == table }
                val actual = db.getTable(table.tableName).rowCount
                if (actual != expected) return "${table.tableName}: expected $expected rows, found $actual"
                indexWalkProblem(db, table)?.let { return it }
            }

            for (op in ops) {
                val id = when (op) {
                    is Op.Insert -> applied.tempKeys[op.tempKey]
                    is Op.Update -> op.row.resolve(applied)
                    is Op.SetDeleted -> op.row.resolve(applied)
                    is Op.HardDelete -> continue
                } ?: return "unresolved row in ${op.table.tableName}"
                val row = CursorBuilder.findRowByPrimaryKey(db.getTable(op.table.tableName), id)
                    ?: return "${op.table.tableName}#$id missing after apply"
                if (op is Op.SetDeleted && (row["del"] == true) != op.new) return "${op.table.tableName}#$id: del flag not stored"
            }
            null
        }

    /** Every index must enumerate exactly the table's rows, otherwise Jet would see a damaged table. */
    private fun indexWalkProblem(db: Database, table: Table): String? {
        val t = db.getTable(table.tableName)
        for (index in t.indexes) {
            val walked = CursorBuilder.createCursor(index).count()
            if (walked != t.rowCount) return "${table.tableName}: index ${index.name} lists $walked of ${t.rowCount} rows"
        }
        return null
    }

    private fun RowRef.resolve(applied: ApplyResult): Long? = when (this) {
        is RowRef.Existing -> id
        is RowRef.New -> applied.tempKeys[tempKey]
    }

    private fun fsync(file: File) =
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }

    // Directories cannot be opened for fsync on Windows; the replace itself is still atomic there.
    private fun fsyncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        } catch (_: UnsupportedOperationException) {
        }
    }
}
