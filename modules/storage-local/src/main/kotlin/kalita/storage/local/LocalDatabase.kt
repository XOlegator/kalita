// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import kalita.storage.api.ChangeSet
import kalita.storage.mdb.AtomicCommit
import kalita.storage.mdb.CommitResult
import java.io.File

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
) : AutoCloseable {
    private var seen: FileFingerprint = FileFingerprint.of(file)

    val mode: OpenMode get() = lock.mode

    /** True if the file differs from what Kalita last read or wrote; remembers the new state. */
    fun checkExternalChange(): Boolean {
        val now = FileFingerprint.of(file)
        val changed = now != seen
        seen = now
        return changed
    }

    fun submit(changeSets: List<ChangeSet>): SubmitOutcome {
        val changed = checkExternalChange()
        val mode = mode
        if (mode is OpenMode.ReadOnly) return SubmitOutcome(CommitResult.Failed("read-only: ${mode.reason}"), changed)

        val backup = try {
            backups.backup(file)
        } catch (e: Exception) {
            return SubmitOutcome(CommitResult.Failed("backup failed: ${e.message}", e), changed)
        }
        val result = AtomicCommit.commit(file, changeSets)
        if (result is CommitResult.Committed) seen = FileFingerprint.of(file) else backup.delete()
        return SubmitOutcome(result, changed)
    }

    override fun close() = lock.close()

    companion object {
        fun open(file: File, backupDirectory: File, keepBackups: Int = 10): LocalDatabase {
            require(file.isFile) { "No such database: $file" }
            return LocalDatabase(file, DatabaseLock.acquire(file), BackupManager(backupDirectory, keepBackups))
        }
    }
}
