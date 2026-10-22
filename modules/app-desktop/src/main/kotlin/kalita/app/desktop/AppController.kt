// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.core.Dataset
import kalita.presentation.DictionaryPresenter
import kalita.presentation.FilterPresenter
import kalita.presentation.JournalPresenter
import kalita.presentation.OperationFormPresenter
import kalita.presentation.SaldoPresenter
import kalita.storage.api.DbLocation
import kalita.storage.api.toDataset
import kalita.storage.api.StorageSession
import kalita.storage.api.SyncStatus
import kalita.storage.local.LocalStorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface AppState {
    data object NoDatabase : AppState
    data class Ready(val filePath: String, val readOnly: Boolean, val dataset: Dataset) : AppState
    data class Error(val message: String) : AppState
}

sealed interface OpenResult {
    data class Success(val readOnly: Boolean) : OpenResult
    data class Failure(val message: String) : OpenResult
}

/**
 * Application lifecycle manager (dev_plan.md §9.9): opens a database via [LocalStorageProvider],
 * converts its snapshot to a [Dataset], and feeds all presenters. Pure Kotlin — no Qt dependency.
 */
class AppController(private val settings: AppSettings) {
    private val _state = MutableStateFlow<AppState>(AppState.NoDatabase)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var session: StorageSession? = null

    val journal = JournalPresenter()
    val operationForm = OperationFormPresenter()
    val saldo = SaldoPresenter()
    val filter = FilterPresenter()
    val dictionary = DictionaryPresenter()

    fun openDatabase(file: File): OpenResult {
        closeDatabase()
        return try {
            val dataDir = dataDirectory(file)
            val sess = LocalStorageProvider.open(DbLocation.Local(file, dataDir))
            session = sess
            val readOnly = sess.status is SyncStatus.ReadOnly
            val dataset = sess.snapshot.toDataset()
            feedPresenters(dataset)
            settings.lastDatabasePath = file.absolutePath
            settings.addRecent(file.absolutePath)
            _state.value = AppState.Ready(file.absolutePath, readOnly, dataset)
            OpenResult.Success(readOnly)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            _state.value = AppState.Error(msg)
            OpenResult.Failure(msg)
        }
    }

    fun closeDatabase() {
        session?.close()
        session = null
        _state.value = AppState.NoDatabase
    }

    fun refresh(): Boolean {
        val sess = session ?: return false
        val changed = sess.refresh()
        if (changed) {
            val dataset = sess.snapshot.toDataset()
            feedPresenters(dataset)
            val readOnly = sess.status is SyncStatus.ReadOnly
            _state.value = AppState.Ready(settings.lastDatabasePath ?: "", readOnly, dataset)
        }
        return changed
    }

    private fun feedPresenters(dataset: Dataset) {
        journal.load(dataset)
        operationForm.load(dataset)
        saldo.load(dataset)
        dictionary.load(dataset)
    }

    private fun dataDirectory(dbFile: File): File {
        val name = dbFile.nameWithoutExtension
        return File(dbFile.parentFile, "$name.kalita-data")
    }
}
