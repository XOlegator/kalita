// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.AuditStamp
import kalita.core.Dataset
import kalita.storage.api.ChangeSetBuilder
import kalita.storage.api.DbLocation
import kalita.storage.api.StorageSession
import kalita.storage.api.SubmitResult
import kalita.storage.api.SyncStatus
import kalita.storage.api.toDataset
import kalita.storage.local.AgentChannelClient
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Opens a `Data.mdb` for a CLI/agent invocation (dev_plan.md §12.15, FR-AG6): same data directory
 * convention and lock as [kalita.app.desktop.AppController] (not imported from here — agent-api
 * must not depend on app-desktop), so both find the same backups/history. If the database is
 * already open elsewhere, [LocalStorageProvider.open] comes back read-only (via
 * [kalita.storage.local.DatabaseLock]) for *reads*, which is fine since any process reads the Jet
 * file directly regardless of the lock; [submit] is the one path that needs the other process's
 * cooperation, via [AgentChannelClient].
 */
class AgentSession private constructor(
    private val session: StorageSession,
    private val file: File,
    val dataDirectory: File,
) : AutoCloseable {

    val dataset: Dataset get() = session.snapshot.toDataset()

    val readOnly: Boolean get() = session.status is SyncStatus.ReadOnly

    /** Reason the database is read-only, if it is (FR-4). */
    val readOnlyReason: String? get() = (session.status as? SyncStatus.ReadOnly)?.reason

    /**
     * Builds and submits one [kalita.storage.api.ChangeSet] (mirrors
     * [kalita.app.desktop.AppController.submitChange]). [author] is `null` for agent-made changes —
     * there is no human user id — the audit trail instead distinguishes an agent's change by
     * [device] (FR-AG5, e.g. `"agent:cli"`), visible in `ChangeHistory`/`Editor` like any other.
     *
     * Tries [AgentChannelClient] first (dev_plan.md §12.15, FR-AG6): if a desktop instance is
     * running with this same database open, it already holds the write lock and [session] here is
     * read-only, so the change set is handed to that instance over the local socket instead of
     * failing. [AgentChannelClient.trySubmit] returns `null` when no such instance is reachable —
     * then [session] does the submit itself, exactly as before this channel existed (succeeding if
     * this process itself holds the write lock, failing read-only otherwise).
     *
     * The returned [SubmitOutcome.changeSetId] is the id this same change set is recorded under in
     * `ChangeHistory` on success (dev_plan.md §12.13's `undo_changes`) — callers that want a change
     * to be undoable later return it to the agent.
     */
    fun submit(description: String, device: String, author: Int? = null, block: (ChangeSetBuilder) -> Unit): SubmitOutcome {
        val audit = AuditStamp(userId = author, timestamp = LocalDateTime.now())
        val builder = ChangeSetBuilder(session.snapshot, audit, device)
        block(builder)
        val changeSet = builder.build(description)
        val result = AgentChannelClient.trySubmit(file, changeSet) ?: session.submit(changeSet)
        return SubmitOutcome(result, changeSet.id)
    }

    /**
     * Re-reads the database if it changed externally (delegates to [StorageSession.refresh]).
     * The CLI never needs this — each invocation opens a fresh [AgentSession] and exits — but
     * `kalita mcp` (dev_plan.md §12.12) holds one [AgentSession] open for the whole lifetime of a
     * client connection, so without an explicit refresh before every read it would keep serving the
     * dataset exactly as of process start even after the desktop app or another process changes the
     * file. Returns true if the content differed from what [dataset] was already returning.
     */
    fun refresh(): Boolean = session.refresh()

    override fun close() = session.close()

    companion object {
        fun open(file: File): AgentSession {
            require(file.isFile) { "No such database: $file" }
            val dataDirectory = defaultDataDirectory(file)
            val session = LocalStorageProvider.open(DbLocation.Local(file, dataDirectory))
            return AgentSession(session, file, dataDirectory)
        }
    }
}

/** [changeSetId] is assigned whether or not [result] is [SubmitResult.Applied] — only meaningful on success. */
data class SubmitOutcome(val result: SubmitResult, val changeSetId: UUID)
