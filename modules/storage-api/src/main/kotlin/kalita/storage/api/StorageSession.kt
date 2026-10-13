// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.api

import java.io.File

/** One row of an application table: column name to plain JVM value (see [FieldChange] for the types). */
typealias Row = Map<String, Any?>

/**
 * Immutable in-memory copy of the application tables (dev_plan.md §9.5: the application part of the
 * database is small enough to be loaded whole). Soft-deleted rows (`del = true`) are included —
 * hiding them is a domain rule, not a storage one.
 */
class DbSnapshot(private val tables: Map<Table, List<Row>>) {
    fun rows(table: Table): List<Row> = tables[table].orEmpty()

    fun row(table: Table, id: Long): Row? = byId(table)[id]

    private val index = HashMap<Table, Map<Long, Row>>()

    @Synchronized
    private fun byId(table: Table): Map<Long, Row> =
        index.getOrPut(table) { rows(table).associateBy { (it["id"] as Number).toLong() } }

    companion object {
        val EMPTY = DbSnapshot(emptyMap())
    }
}

sealed interface DbLocation {
    /** [dataDirectory] is the device-local folder for backups and history (§9.4). */
    data class Local(val file: File, val dataDirectory: File) : DbLocation
}

sealed interface SyncStatus {
    /** The file is on this device and writable. */
    data object Local : SyncStatus

    /** Writing is refused, e.g. because the original X-Cash or another Kalita has the file open (FR-4). */
    data class ReadOnly(val reason: String) : SyncStatus
}

sealed interface SubmitResult {
    /** Applied; [tempKeys] maps the inserted rows to their real ids. */
    data class Applied(val tempKeys: Map<TempKey, Long>) : SubmitResult

    /** Nothing was written; the change set stays valid only once the conflicts are resolved. */
    data class Conflicts(val conflicts: List<Conflict>) : SubmitResult

    /** Nothing was written (read-only mode, I/O error, failed verification, unknown schema). */
    data class Failed(val reason: String, val cause: Throwable? = null) : SubmitResult
}

interface StorageProvider {
    fun open(location: DbLocation): StorageSession
}

/** An opened database: current [snapshot] plus the single write path [submit] (dev_plan.md §9.5). */
interface StorageSession : AutoCloseable {
    val snapshot: DbSnapshot
    val status: SyncStatus

    /** Applies one user action atomically; on [SubmitResult.Applied] [snapshot] already reflects it. */
    fun submit(change: ChangeSet): SubmitResult

    /** Re-reads the database (e.g. after an external change, FR-8). Returns true if the content differs from [snapshot]. */
    fun refresh(): Boolean
}
