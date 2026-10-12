// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.mdb.AtomicCommit
import kalita.storage.mdb.CommitResult
import java.io.File
import java.time.Instant

/** Result of [LocalDatabase.submit]; [externalChange] tells that the file was edited outside Kalita since last seen (FR-8). */
data class SubmitOutcome(val result: CommitResult, val externalChange: Boolean)

/**
 * A local `Data.mdb` opened for work: lock (FR-4), external-change detection (FR-8) and a backup
 * before every write, with the atomic commit of §9.6 underneath. Change sets queued on top of an
 * externally modified file are checked against the new content by the commit itself (their base
 * values must still match), so a changed file is simply re-read, never overwritten blindly.
 */
class LocalDatabase private constructor(
    val file: File,
    private val lock: DatabaseLock,
    private val backups: BackupManager,
    val history: ChangeHistory,
) : AutoCloseable {
    private var seen: FileFingerprint = FileFingerprint.of(file)

    val mode: OpenMode get() = lock.mode

    /** True if the file differs from what Kalita last read or wrote; remembers the new state. */
    @Synchronized
    fun checkExternalChange(): Boolean {
        val now = FileFingerprint.of(file)
        val changed = now != seen
        seen = now
        return changed
    }

    @Synchronized
    fun submit(changeSets: List<ChangeSet>): SubmitOutcome {
        val changed = checkExternalChange()
        val mode = mode
        if (mode is OpenMode.ReadOnly) return SubmitOutcome(CommitResult.Failed("read-only: ${mode.reason}"), changed)
        DatabaseLock.jetLockFile(file)?.let {
            return SubmitOutcome(CommitResult.Failed("the original X-Cash has opened the database (${it.name} exists)"), changed)
        }
        if (changeSets.all { it.ops.isEmpty() }) return SubmitOutcome(CommitResult.Committed(emptyMap()), changed)
        try {
            changeSets.forEach(history::checkStorable)
        } catch (e: IllegalArgumentException) {
            return SubmitOutcome(CommitResult.Failed("cannot record change in history: ${e.message}", e), changed)
        }

        val backup = try {
            backups.backup(file)
        } catch (e: Exception) {
            return SubmitOutcome(CommitResult.Failed("backup failed: ${e.message}", e), changed)
        }
        val result = AtomicCommit.commit(file, changeSets)
        if (result is CommitResult.Committed) {
            // The data is committed: nothing below may turn that into an error the caller would
            // retry (and so apply twice). A failed re-fingerprint only means a spurious "external
            // change" on the next check.
            runCatching { seen = FileFingerprint.of(file) }
            runCatching { backups.prune(file.nameWithoutExtension) }
            recordHistory(changeSets, result)
        } else {
            backup.delete()
        }
        return SubmitOutcome(result, changed)
    }

    // Values were checked by checkStorable before the commit, so only I/O can fail here.
    private fun recordHistory(changeSets: List<ChangeSet>, result: CommitResult.Committed) {
        runCatching {
            val now = Instant.now()
            history.append(changeSets.map { cs ->
                val own = cs.ops.filterIsInstance<Op.Insert>().map { it.tempKey }.toSet()
                HistoryEntry(cs, now, result.tempKeys.filterKeys { it in own })
            })
        }
    }

    override fun close() = lock.close()

    companion object {
        /** [dataDirectory] is the per-database device-local folder of §9.4: `backups/` and `history.jsonl` live there. */
        fun open(file: File, dataDirectory: File, keepBackups: Int = 10): LocalDatabase {
            require(file.isFile) { "No such database: $file" }
            val backups = BackupManager(File(dataDirectory, "backups"), keepBackups)
            val lock = DatabaseLock.acquire(file)
            return try {
                LocalDatabase(file, lock, backups, ChangeHistory(File(dataDirectory, "history.jsonl")))
            } catch (e: Exception) {
                lock.close()
                throw e
            }
        }
    }
}
