// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import kalita.storage.api.ChangeSet
import kalita.storage.api.Conflict
import kalita.storage.mdb.AtomicCommit
import kalita.storage.mdb.CommitResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** What [NextcloudDatabase.trySync] actually did — maps straight to [kalita.storage.api.SyncStatus]. */
sealed interface SyncOutcome {
    /** Nothing queued, server unchanged — already in sync. */
    data object UpToDate : SyncOutcome

    /** The whole queue (or the non-conflicting prefix of it) was confirmed on the server. */
    data object Pushed : SyncOutcome

    /** Nothing of ours to send; picked up a newer version from someone else. */
    data object PulledOnly : SyncOutcome

    /** A queued change set could not be rebased onto the server's latest version (§10.5). */
    data class Conflicted(val conflicts: List<Conflict>) : SyncOutcome

    /** Couldn't reach the server; the local copy (and queue) are untouched and still usable. */
    data class Offline(val reason: String) : SyncOutcome
}

/**
 * One Nextcloud-backed `Data.mdb`: a private local cache file plus the queue of change sets not
 * yet confirmed on the server (dev_plan.md §10). [submit] only ever touches the local cache —
 * never the network, so it's as fast as the local-mode equivalent and the user is never kept
 * waiting on it (§10.2); [trySync] is the separate, explicit network round trip (§10.2's
 * `PROPFIND`/`GET`/rebase/`PUT` cycle), reusing `storage-mdb`'s `AtomicCommit`/`MdbEngine` against
 * the cache file exactly as `LocalDatabase` uses them against the shared local file.
 */
class NextcloudDatabase private constructor(
    val file: File,
    private val client: NextcloudClient,
    private val remotePath: String,
    private val queue: PendingQueue,
    private val stateFile: File,
    private val lock: CacheLock,
    private var baseEtag: String,
) : AutoCloseable {
    val queueSize: Int get() = queue.read().size

    @Synchronized
    fun submit(changeSet: ChangeSet): CommitResult {
        val result = AtomicCommit.commit(file, listOf(changeSet))
        if (result is CommitResult.Committed) queue.append(changeSet)
        return result
    }

    /** The §10.2 sync cycle — see [SyncOutcome] for what it can come back with. */
    @Synchronized
    fun trySync(maxRaceRetries: Int = 5): SyncOutcome {
        repeat(maxRaceRetries) {
            when (val outcome = trySyncOnce()) {
                is RaceRetry -> Unit // someone else's PUT landed between our stat and ours — loop and retry
                else -> return outcome
            }
        }
        return SyncOutcome.Offline("gave up after $maxRaceRetries attempts racing another writer")
    }

    private object RaceRetry : SyncOutcome

    private fun trySyncOnce(): SyncOutcome {
        val pending = queue.read()
        val remoteInfo = try {
            client.stat(remotePath)
        } catch (e: IOException) {
            return SyncOutcome.Offline(e.message ?: e.javaClass.simpleName)
        } ?: return SyncOutcome.Offline("the file no longer exists on the server")

        // Queue items not yet reflected in the cache file: only non-empty right after a rebase
        // below, since `submit` already committed every queued item to `file` as it was added.
        var rebased: List<ChangeSet> = emptyList()
        var conflicts: List<Conflict> = emptyList()
        val pulled = remoteInfo.etag != baseEtag
        if (pulled) {
            val remote = try {
                client.get(remotePath)
            } catch (e: IOException) {
                return SyncOutcome.Offline(e.message ?: e.javaClass.simpleName)
            } ?: return SyncOutcome.Offline("the file no longer exists on the server")
            file.writeBytes(remote.bytes)
            baseEtag = remote.etag
            writeState()
            for (cs in pending) {
                when (val result = AtomicCommit.commit(file, listOf(cs))) {
                    is CommitResult.Committed -> rebased = rebased + cs
                    is CommitResult.Conflicts -> {
                        conflicts = result.conflicts
                        break
                    }
                    is CommitResult.Failed -> return SyncOutcome.Offline(result.reason)
                }
            }
        } else {
            rebased = pending
        }

        if (rebased.isEmpty()) {
            return when {
                conflicts.isNotEmpty() -> SyncOutcome.Conflicted(conflicts)
                pulled -> SyncOutcome.PulledOnly
                else -> SyncOutcome.UpToDate
            }
        }

        return when (val put = client.put(remotePath, file.readBytes(), baseEtag)) {
            is NextcloudPutResult.Applied -> {
                baseEtag = put.etag
                writeState()
                queue.removeFirst(rebased.size)
                if (conflicts.isNotEmpty()) SyncOutcome.Conflicted(conflicts) else SyncOutcome.Pushed
            }
            is NextcloudPutResult.PreconditionFailed -> RaceRetry
            is NextcloudPutResult.Failed -> SyncOutcome.Offline("HTTP ${put.code}: ${put.message}")
        }
    }

    private fun writeState() {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(buildJsonObject { put("baseEtag", baseEtag) }.toString(), Charsets.UTF_8)
    }

    override fun close() = lock.close()

    companion object {
        /**
         * Opens the cache under [dataDirectory] if one already exists (offline-first — no network
         * needed); otherwise this is the first time this device sees this location (FR-S2 "Открыть
         * базу из Nextcloud"), so it downloads the current server copy to seed the cache. Throws if
         * the cache directory is already locked by another process on this device, or the initial
         * download fails.
         */
        fun open(server: String, loginName: String, appPassword: String, remotePath: String, dataDirectory: File): NextcloudDatabase {
            val lock = CacheLock.tryAcquire(dataDirectory) ?: error("another Kalita process already has this database open on this device")
            try {
                val client = NextcloudClient(server, loginName, appPassword)
                val file = File(dataDirectory, "server-copy.mdb")
                val stateFile = File(dataDirectory, "sync-state.json")
                val baseEtag = if (file.isFile && stateFile.isFile) {
                    readState(stateFile)
                } else {
                    val remote = client.get(remotePath) ?: error("no such file on the server: $remotePath")
                    file.writeBytes(remote.bytes)
                    remote.etag
                }
                val db = NextcloudDatabase(file, client, remotePath, PendingQueue(File(dataDirectory, "queue.json")), stateFile, lock, baseEtag)
                if (!stateFile.isFile) db.writeState()
                return db
            } catch (e: Exception) {
                lock.close()
                throw e
            }
        }

        /** FR-S1 "Переместить базу в Nextcloud": seeds a fresh cache for a database just uploaded by [NextcloudStorageProvider.moveLocalDatabaseToNextcloud]. */
        internal fun seedCache(localFile: File, etag: String, dataDirectory: File) {
            dataDirectory.mkdirs()
            File(dataDirectory, "server-copy.mdb").writeBytes(localFile.readBytes())
            File(dataDirectory, "sync-state.json").writeText(buildJsonObject { put("baseEtag", etag) }.toString(), Charsets.UTF_8)
        }

        private fun readState(stateFile: File): String =
            Json.parseToJsonElement(stateFile.readText(Charsets.UTF_8)).jsonObject.getValue("baseEtag").jsonPrimitive.content
    }
}
