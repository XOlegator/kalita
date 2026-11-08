// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import kalita.storage.api.ChangeSet
import kalita.storage.api.DbLocation
import kalita.storage.api.DbSnapshot
import kalita.storage.api.StorageProvider
import kalita.storage.api.StorageSession
import kalita.storage.api.SubmitResult
import kalita.storage.api.SyncStatus
import kalita.storage.mdb.CommitResult
import kalita.storage.mdb.MdbSnapshotReader
import java.io.File

/** [StorageProvider] for a `Data.mdb` kept on Nextcloud (dev_plan.md §10 — "network mode"). */
object NextcloudStorageProvider : StorageProvider {
    override fun open(location: DbLocation): StorageSession {
        location as DbLocation.Nextcloud
        val db = NextcloudDatabase.open(location.server, location.loginName, location.appPassword, location.remotePath, location.dataDirectory)
        return try {
            NextcloudStorageSession(db, MdbSnapshotReader.read(db.file))
        } catch (e: Exception) {
            db.close()
            throw e
        }
    }

    sealed interface MoveResult {
        data object Moved : MoveResult
        data object AlreadyExistsOnServer : MoveResult
        data class Failed(val reason: String) : MoveResult
    }

    /**
     * FR-S1 "Переместить базу в Nextcloud": uploads [localFile]'s current bytes as the initial
     * server copy at [location]'s remote path with `putIfAbsent` — never silently clobbering a
     * file already there — then seeds [location]'s local cache so a subsequent [open] of the same
     * [location] works straight away, offline, with nothing left to send. Doesn't open a session
     * itself; call [open] with the same [location] afterward.
     */
    fun moveLocalDatabaseToNextcloud(localFile: File, location: DbLocation.Nextcloud): MoveResult {
        val client = NextcloudClient(location.server, location.loginName, location.appPassword)
        return when (val result = client.putIfAbsent(location.remotePath, localFile.readBytes())) {
            is NextcloudPutResult.Applied -> {
                NextcloudDatabase.seedCache(localFile, result.etag, location.dataDirectory)
                MoveResult.Moved
            }
            is NextcloudPutResult.PreconditionFailed -> MoveResult.AlreadyExistsOnServer
            is NextcloudPutResult.Failed -> MoveResult.Failed("HTTP ${result.code}: ${result.message}")
        }
    }
}

internal class NextcloudStorageSession(private val db: NextcloudDatabase, initial: DbSnapshot) : StorageSession {
    @Volatile
    override var snapshot: DbSnapshot = initial
        private set

    @Volatile
    private var lastOutcome: SyncOutcome = SyncOutcome.UpToDate

    override val status: SyncStatus
        get() = when (val outcome = lastOutcome) {
            is SyncOutcome.Offline -> SyncStatus.Offline(outcome.reason)
            is SyncOutcome.Conflicted -> SyncStatus.Conflicted(outcome.conflicts)
            else -> if (db.queueSize > 0) SyncStatus.Pending(db.queueSize) else SyncStatus.Synced
        }

    /** Local only (§10.2: "пользователь никогда не ждёт сети") — the network round trip is [refresh]. */
    @Synchronized
    override fun submit(change: ChangeSet): SubmitResult =
        when (val result = db.submit(change)) {
            is CommitResult.Committed -> {
                // Already committed: a failed re-read below must not look like a failed write.
                runCatching { snapshot = MdbSnapshotReader.read(db.file) }
                SubmitResult.Applied(result.tempKeys)
            }
            is CommitResult.Conflicts -> SubmitResult.Conflicts(result.conflicts)
            is CommitResult.Failed -> SubmitResult.Failed(result.reason, result.cause)
        }

    /** The §10.2 sync cycle: push the queue, pull anything newer, rebase on conflict. */
    @Synchronized
    override fun refresh(): Boolean {
        val before = snapshot
        val outcome = db.trySync()
        lastOutcome = outcome
        // Offline leaves the cache file untouched; the other outcomes may have rewritten it
        // (a fresh download, a rebase, or both), so only those are worth a re-read.
        if (outcome is SyncOutcome.Pushed || outcome is SyncOutcome.PulledOnly || outcome is SyncOutcome.Conflicted) {
            runCatching { snapshot = MdbSnapshotReader.read(db.file) }
        }
        return snapshot !== before
    }

    override fun close() = db.close()
}
