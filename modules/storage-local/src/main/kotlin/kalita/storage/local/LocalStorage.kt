// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import kalita.storage.api.ChangeSet
import kalita.storage.api.DbLocation
import kalita.storage.api.DbSnapshot
import kalita.storage.api.StorageProvider
import kalita.storage.api.StorageSession
import kalita.storage.api.SubmitResult
import kalita.storage.api.SyncStatus
import kalita.storage.mdb.CommitResult
import kalita.storage.mdb.MdbSnapshotReader

/** [StorageProvider] for a `Data.mdb` on a local disk (dev_plan.md §9.5 "local mode"). */
object LocalStorageProvider : StorageProvider {
    override fun open(location: DbLocation): StorageSession {
        location as DbLocation.Local
        val db = LocalDatabase.open(location.file, location.dataDirectory)
        return try {
            LocalStorageSession(db, MdbSnapshotReader.read(db.file))
        } catch (e: Exception) {
            db.close()
            throw e
        }
    }
}

internal class LocalStorageSession(private val db: LocalDatabase, initial: DbSnapshot) : StorageSession {
    @Volatile
    override var snapshot: DbSnapshot = initial
        private set

    override val status: SyncStatus
        get() = when (val mode = db.mode) {
            is OpenMode.ReadOnly -> SyncStatus.ReadOnly(mode.reason)
            else -> SyncStatus.Local
        }

    @Synchronized
    override fun submit(change: ChangeSet): SubmitResult =
        when (val result = db.submit(listOf(change)).result) {
            is CommitResult.Committed -> {
                // The data is already committed: a failed re-read must not look like a failed write.
                runCatching { snapshot = MdbSnapshotReader.read(db.file) }
                SubmitResult.Applied(result.tempKeys)
            }
            is CommitResult.Conflicts -> SubmitResult.Conflicts(result.conflicts)
            is CommitResult.Failed -> SubmitResult.Failed(result.reason, result.cause)
        }

    @Synchronized
    override fun refresh(): Boolean {
        val changed = db.checkExternalChange()
        if (changed) snapshot = MdbSnapshotReader.read(db.file)
        return changed
    }

    override fun close() = db.close()
}
