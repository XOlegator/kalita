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
import kalita.storage.local.toDataset
import kalita.storage.local.AgentChannelClient
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Opens a snapshot of `Data.mdb` for CLI/MCP without holding the write lock (dev_plan.md §12.15, FR-AG6).
 * The desktop and agent share the same backups and history directory. When writing, [submit]
 * either goes through [AgentChannelClient] or acquires the same lock as the desktop for one commit.
 */
class AgentSession private constructor(
    private val session: StorageSession,
    private val file: File?,
    val dataDirectory: File,
    private val ownsSession: Boolean = true,
) : AutoCloseable {

    /** The data as the shells show it, the user's own base-currency choice ([kalita.storage.local.DatabaseSettings]) included. */
    val dataset: Dataset get() = session.snapshot.toDataset(dataDirectory)

    val readOnly: Boolean get() = session.status is SyncStatus.ReadOnly

    /** Reason the database is read-only, if it is (FR-4). */
    val readOnlyReason: String? get() = (session.status as? SyncStatus.ReadOnly)?.reason

    /**
     * Builds and submits one [kalita.storage.api.ChangeSet] (mirrors
     * [kalita.app.desktop.AppController.submitChange]). Without an explicit [author] the rows are
     * stamped with the database's own usual user ([kalita.storage.api.DbSnapshot.auditUserId]), the
     * same one the desktop and Android shells use — a row with empty `Creator`/`Editor` looks
     * unfinished next to everything the original X-Cash wrote. What distinguishes an agent's change
     * is [device] (FR-AG5, e.g. `"agent:cli"`), recorded in `ChangeHistory`.
     *
     * Tries [AgentChannelClient] first (dev_plan.md §12.15, FR-AG6): if a desktop instance is
     * running with this same database open, it owns the lock and commits the change set. If the
     * channel is unavailable, [session] tries to acquire the lock for the duration of the commit.
     *
     * The returned [SubmitOutcome.changeSetId] is the id this same change set is recorded under in
     * `ChangeHistory` on success (dev_plan.md §12.13's `undo_changes`) — callers that want a change
     * to be undoable later return it to the agent.
     */
    fun submit(description: String, device: String, author: Int? = null, block: (ChangeSetBuilder) -> Unit): SubmitOutcome {
        val audit = AuditStamp(userId = author ?: session.snapshot.auditUserId, timestamp = LocalDateTime.now())
        val builder = ChangeSetBuilder(session.snapshot, audit, device)
        block(builder)
        val changeSet = builder.build(description)
        val result = file?.let { AgentChannelClient.trySubmit(it, changeSet) } ?: session.submit(changeSet)
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

    override fun close() {
        if (ownsSession) session.close()
    }

    companion object {
        /** Uses an existing shell session without taking ownership or reopening the database. */
        fun borrow(session: StorageSession, dataDirectory: File): AgentSession =
            AgentSession(session, null, dataDirectory, ownsSession = false)

        fun open(file: File): AgentSession {
            require(file.isFile) { "No such database: $file" }
            val dataDirectory = defaultDataDirectory(file)
            val session = LocalStorageProvider.openForAgent(DbLocation.Local(file, dataDirectory))
            return AgentSession(session, file, dataDirectory)
        }
    }
}

/** [changeSetId] is assigned whether or not [result] is [SubmitResult.Applied] — only meaningful on success. */
data class SubmitOutcome(val result: SubmitResult, val changeSetId: UUID)
