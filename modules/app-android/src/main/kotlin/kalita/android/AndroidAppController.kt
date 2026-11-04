// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.core.Dataset
import kalita.presentation.JournalPresenter
import kalita.storage.api.DbLocation
import kalita.storage.api.StorageSession
import kalita.storage.api.SyncStatus
import kalita.storage.api.toDataset
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

sealed interface AndroidAppState {
    data object NoDatabase : AndroidAppState
    data class Ready(val readOnly: Boolean, val dataset: Dataset) : AndroidAppState
    data class Error(val message: String) : AndroidAppState
}

/**
 * FR-A1's local half (dev_plan.md Этап 10): "local database in app storage, import/export an
 * `.mdb` from anywhere via the system file picker" — scoped storage gives a picked SAF `Uri` no
 * stable [File] path of its own, and storage-local's locking/backups/history
 * ([kalita.storage.local.DataDirectory]) all assume a real one, so the picked file is copied into
 * [filesDir] once and opened from there — the same [LocalStorageProvider] the desktop app
 * ([kalita.app.desktop.AppController]) uses, so a database either one touches sees the same lock,
 * backups and history as the other. Read-only for now (FR-A3/A4 — editing — are a later slice);
 * [kalita.storage.local.LocalDatabase] already reports [SyncStatus.ReadOnly] by itself whenever a
 * write lock is already held elsewhere, same as the desktop app.
 *
 * FR-A1 also asks for export back out and for "the last database opens at launch" — there is
 * only ever one local copy ([databaseFile], fixed at `filesDir/Data.mdb`), so "last database"
 * needs no separate bookkeeping (no `SharedPreferences`, unlike the desktop app's
 * `AppSettings.lastDatabasePath`): launch just has to check whether that one file is already
 * there. Reopening it is safe across process restarts — [kalita.storage.local.DatabaseLock]
 * releases its OS [java.nio.channels.FileLock] on close but deliberately leaves the lock file
 * itself on disk, so a fresh process can re-acquire it without issue.
 */
class AndroidAppController(private val filesDir: File) {
    private val _state = MutableStateFlow<AndroidAppState>(AndroidAppState.NoDatabase)
    val state: StateFlow<AndroidAppState> = _state.asStateFlow()

    val journal = JournalPresenter()

    private var session: StorageSession? = null

    private val databaseFile: File get() = File(filesDir, "Data.mdb")

    /** If `Data.mdb` is already there from a previous launch, opens it; otherwise does nothing. */
    fun openIfExists() {
        if (databaseFile.isFile) {
            openPrivateFile()
        }
    }

    /** Copies [input] into [filesDir] as `Data.mdb`, replacing whatever was open before. */
    fun importAndOpen(input: InputStream) {
        closeDatabase()
        try {
            databaseFile.outputStream().use { out -> input.copyTo(out) }
            openPrivateFile()
        } catch (e: Exception) {
            _state.value = AndroidAppState.Error(e.message ?: e.toString())
        }
    }

    /**
     * Copies the current `Data.mdb` out to [output] (e.g. a SAF-picked destination). A plain
     * byte copy is safe while the session is open — same as [kalita.storage.local.BackupManager],
     * which copies the live file mid-session before every write; Jackcess has no separate WAL
     * state, and the OS lock guarding concurrent writers sits on a sidecar lock file, not on
     * `Data.mdb` itself.
     */
    fun exportTo(output: OutputStream) {
        databaseFile.inputStream().use { it.copyTo(output) }
    }

    private fun openPrivateFile() {
        try {
            val sess = LocalStorageProvider.open(DbLocation.Local(databaseFile, defaultDataDirectory(databaseFile)))
            session = sess
            val dataset = sess.snapshot.toDataset()
            journal.load(dataset)
            _state.value = AndroidAppState.Ready(sess.status is SyncStatus.ReadOnly, dataset)
        } catch (e: Exception) {
            _state.value = AndroidAppState.Error(e.message ?: e.toString())
        }
    }

    fun closeDatabase() {
        session?.close()
        session = null
        _state.value = AndroidAppState.NoDatabase
    }
}
