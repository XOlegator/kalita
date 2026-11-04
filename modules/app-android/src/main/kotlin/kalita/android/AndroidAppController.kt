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
 */
class AndroidAppController(private val filesDir: File) {
    private val _state = MutableStateFlow<AndroidAppState>(AndroidAppState.NoDatabase)
    val state: StateFlow<AndroidAppState> = _state.asStateFlow()

    val journal = JournalPresenter()

    private var session: StorageSession? = null

    /** Copies [input] into [filesDir] as `Data.mdb`, replacing whatever was open before. */
    fun importAndOpen(input: InputStream) {
        closeDatabase()
        val file = File(filesDir, "Data.mdb")
        try {
            file.outputStream().use { out -> input.copyTo(out) }
            val sess = LocalStorageProvider.open(DbLocation.Local(file, defaultDataDirectory(file)))
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
