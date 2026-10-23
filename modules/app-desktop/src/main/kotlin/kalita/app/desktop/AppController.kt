// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.core.AuditStamp
import kalita.core.BudgetTreeService
import kalita.core.Dataset
import kalita.core.MoveResult
import kalita.presentation.DictionaryPresenter
import kalita.presentation.FilterPresenter
import kalita.presentation.JournalPresenter
import kalita.presentation.OperationFormPresenter
import kalita.presentation.RatePresenter
import kalita.presentation.SaldoPresenter
import kalita.storage.api.ChangeSet
import kalita.storage.api.ChangeSetBuilder
import kalita.storage.api.DbLocation
import kalita.storage.api.StorageSession
import kalita.storage.api.SubmitResult
import kalita.storage.api.SyncStatus
import kalita.storage.api.Table
import kalita.storage.api.toDataset
import kalita.storage.local.LocalStorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

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
    val rate = RatePresenter()

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

    // --- Dictionary CRUD (FR-10…FR-14) ---

    fun addAccount(name: String): SubmitResult = submitChange("Добавить счёт «$name»") { builder ->
        builder.insert(Table.Accounts, builder.nextTempKey(), mapOf("Name" to name))
    }

    fun updateAccount(id: Long, name: String): SubmitResult = submitChange("Изменить счёт") { builder ->
        builder.update(Table.Accounts, id, mapOf("Name" to name))
    }

    fun softDeleteAccount(id: Long): SubmitResult = submitChange("Удалить счёт") { builder ->
        builder.softDelete(Table.Accounts, id)
    }

    fun restoreAccount(id: Long): SubmitResult = submitChange("Восстановить счёт") { builder ->
        builder.restore(Table.Accounts, id)
    }

    fun addCounterparty(name: String): SubmitResult = submitChange("Добавить «$name»") { builder ->
        builder.insert(Table.People, builder.nextTempKey(), mapOf("Name" to name))
    }

    fun updateCounterparty(id: Long, name: String): SubmitResult = submitChange("Изменить кто/кому") { builder ->
        builder.update(Table.People, id, mapOf("Name" to name))
    }

    fun softDeleteCounterparty(id: Long): SubmitResult = submitChange("Удалить кто/кому") { builder ->
        builder.softDelete(Table.People, id)
    }

    fun restoreCounterparty(id: Long): SubmitResult = submitChange("Восстановить кто/кому") { builder ->
        builder.restore(Table.People, id)
    }

    fun addCurrency(name: String, cbId: String?): SubmitResult = submitChange("Добавить валюту «$name»") { builder ->
        builder.insert(Table.Currencies, builder.nextTempKey(), mapOf("Name" to name, "CB_ID" to cbId))
    }

    fun updateCurrency(id: Long, name: String, cbId: String?): SubmitResult = submitChange("Изменить валюту") { builder ->
        builder.update(Table.Currencies, id, mapOf("Name" to name, "CB_ID" to cbId))
    }

    fun softDeleteCurrency(id: Long): SubmitResult = submitChange("Удалить валюту") { builder ->
        builder.softDelete(Table.Currencies, id)
    }

    fun restoreCurrency(id: Long): SubmitResult = submitChange("Восстановить валюту") { builder ->
        builder.restore(Table.Currencies, id)
    }

    // --- Budget items (FR-11, §5.4) ---

    fun addBudgetItem(parentId: Long?, name: String): SubmitResult = submitChange("Добавить статью «$name»") { builder ->
        builder.insert(Table.BudgetItems, builder.nextTempKey(), buildMap {
            put("Name", name)
            put("Parent_ID", parentId)
        })
    }

    fun updateBudgetItem(id: Long, name: String): SubmitResult = submitChange("Изменить статью") { builder ->
        builder.update(Table.BudgetItems, id, mapOf("Name" to name))
    }

    fun softDeleteBudgetItem(id: Long): SubmitResult = submitChange("Удалить статью") { builder ->
        builder.softDelete(Table.BudgetItems, id)
    }

    fun restoreBudgetItem(id: Long): SubmitResult = submitChange("Восстановить статью") { builder ->
        builder.restore(Table.BudgetItems, id)
    }

    fun moveBudgetItemUp(id: Long): SubmitResult {
        val dataset = currentDataset() ?: return SubmitResult.Failed("Нет открытой базы")
        val service = BudgetTreeService(dataset)
        return when (val result = service.moveUp(id)) {
            is MoveResult.Ok -> submitChange("Переместить статью вверх") { builder ->
                builder.applyTreeChanges(result.changes)
            }
            MoveResult.AlreadyAtBound -> SubmitResult.Failed("Элемент уже вверху")
            MoveResult.Protected -> SubmitResult.Failed("Корневая статья типа операции защищена")
            MoveResult.ItemNotFound -> SubmitResult.Failed("Элемент не найден")
            else -> SubmitResult.Failed("Операция невозможна")
        }
    }

    fun moveBudgetItemDown(id: Long): SubmitResult {
        val dataset = currentDataset() ?: return SubmitResult.Failed("Нет открытой базы")
        val service = BudgetTreeService(dataset)
        return when (val result = service.moveDown(id)) {
            is MoveResult.Ok -> submitChange("Переместить статью вниз") { builder ->
                builder.applyTreeChanges(result.changes)
            }
            MoveResult.AlreadyAtBound -> SubmitResult.Failed("Элемент уже внизу")
            MoveResult.Protected -> SubmitResult.Failed("Корневая статья типа операции защищена")
            MoveResult.ItemNotFound -> SubmitResult.Failed("Элемент не найден")
            else -> SubmitResult.Failed("Операция невозможна")
        }
    }

    fun moveBudgetItem(id: Long, newParentId: Long?): SubmitResult {
        val dataset = currentDataset() ?: return SubmitResult.Failed("Нет открытой базы")
        val service = BudgetTreeService(dataset)
        return when (val result = service.move(id, newParentId)) {
            is MoveResult.Ok -> submitChange("Переместить статью") { builder ->
                builder.applyTreeChanges(result.changes)
            }
            MoveResult.WouldCycle -> SubmitResult.Failed("Нельзя переместить в собственное поддерево")
            MoveResult.SameParent -> SubmitResult.Failed("Элемент уже в этом узле")
            MoveResult.Protected -> SubmitResult.Failed("Корневая статья типа операции защищена")
            MoveResult.ItemNotFound -> SubmitResult.Failed("Элемент не найден")
            else -> SubmitResult.Failed("Операция невозможна")
        }
    }

    // --- Exchange rates (FR-12, §5.5) ---

    fun addRate(currencyId: Long, date: LocalDate, value: Double): SubmitResult =
        submitChange("Добавить курс") { builder ->
            builder.insert(Table.Rates, builder.nextTempKey(), mapOf(
                "Currency_ID" to currencyId,
                "Date" to date.atStartOfDay(),
                "Course" to value,
            ))
        }

    fun updateRate(id: Long, currencyId: Long, date: LocalDate, value: Double): SubmitResult =
        submitChange("Изменить курс") { builder ->
            builder.update(Table.Rates, id, mapOf(
                "Currency_ID" to currencyId,
                "Date" to date.atStartOfDay(),
                "Course" to value,
            ))
        }

    fun hardDeleteRate(id: Long): SubmitResult = submitChange("Удалить курс") { builder ->
        builder.hardDelete(Table.Rates, id)
    }

    // --- Internal ---

    private fun currentDataset(): Dataset? = (_state.value as? AppState.Ready)?.dataset

    private fun submitChange(description: String, block: (ChangeSetBuilder) -> Unit): SubmitResult {
        val sess = session ?: return SubmitResult.Failed("Нет открытой базы")
        if (sess.status is SyncStatus.ReadOnly) return SubmitResult.Failed("База открыта только для чтения")
        val audit = AuditStamp(userId = 1, timestamp = LocalDateTime.now())
        val builder = ChangeSetBuilder(sess.snapshot, audit, "desktop")
        block(builder)
        val changeSet = builder.build(description)
        val result = sess.submit(changeSet)
        if (result is SubmitResult.Applied) {
            val dataset = sess.snapshot.toDataset()
            feedPresenters(dataset)
            val readOnly = sess.status is SyncStatus.ReadOnly
            _state.value = AppState.Ready(settings.lastDatabasePath ?: "", readOnly, dataset)
        }
        return result
    }

    private fun feedPresenters(dataset: Dataset) {
        journal.load(dataset)
        operationForm.load(dataset)
        saldo.load(dataset)
        dictionary.load(dataset)
        rate.load(dataset)
    }

    private fun dataDirectory(dbFile: File): File {
        val name = dbFile.nameWithoutExtension
        return File(dbFile.parentFile, "$name.kalita-data")
    }
}
