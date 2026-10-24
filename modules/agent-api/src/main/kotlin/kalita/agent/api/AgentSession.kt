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
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import java.io.File
import java.time.LocalDateTime

/**
 * Opens a `Data.mdb` for a CLI/agent invocation (dev_plan.md §12.15, FR-AG6): same data directory
 * convention and lock as [kalita.app.desktop.AppController] (not imported from here — agent-api
 * must not depend on app-desktop), so both find the same backups/history. There is no local-socket
 * channel to a running desktop instance yet (that is a later slice) — if the database is already
 * open elsewhere, [LocalStorageProvider.open] simply comes back read-only (via [DatabaseLock]),
 * which is correct, if not ideal, until that channel exists.
 */
class AgentSession private constructor(
    private val session: StorageSession,
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
     */
    fun submit(description: String, device: String, author: Int? = null, block: (ChangeSetBuilder) -> Unit): SubmitResult {
        val audit = AuditStamp(userId = author, timestamp = LocalDateTime.now())
        val builder = ChangeSetBuilder(session.snapshot, audit, device)
        block(builder)
        return session.submit(builder.build(description))
    }

    override fun close() = session.close()

    companion object {
        fun open(file: File): AgentSession {
            require(file.isFile) { "No such database: $file" }
            val dataDirectory = defaultDataDirectory(file)
            val session = LocalStorageProvider.open(DbLocation.Local(file, dataDirectory))
            return AgentSession(session, dataDirectory)
        }
    }
}
