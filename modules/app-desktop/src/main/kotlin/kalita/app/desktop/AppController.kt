// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.agent.api.AgentConnectionStore
import kalita.core.AmountCondition
import kalita.core.AuditStamp
import kalita.core.BudgetTreeService
import kalita.core.Dataset
import kalita.core.DateCondition
import kalita.core.FilterCondition
import kalita.core.JournalFilter
import kalita.core.MoveResult
import kalita.core.OperationDraft
import kalita.core.RefCondition
import kalita.core.TextCondition
import kalita.platform.DesktopSecretStore
import kalita.platform.SecretStore
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
import kalita.storage.local.AgentChannelServer
import kalita.storage.local.LocalStorageProvider
import kalita.sync.NextcloudCredentials
import kalita.sync.NextcloudStorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/** Identifies which journal field a [FilterCondition] belongs to — used to let one source of
 * conditions (the quick filter) override another (the filter panel) for the same field. */
internal fun FilterCondition.fieldKey(): String = when (this) {
    is DateCondition -> "date"
    is RefCondition -> "ref:$field"
    is AmountCondition -> "amount"
    is TextCondition -> "text:$field"
}

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
class AppController(private val settings: AppSettings, private val secretStore: SecretStore = DesktopSecretStore) {
    private val _state = MutableStateFlow<AppState>(AppState.NoDatabase)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var session: StorageSession? = null
    private var agentChannel: AgentChannelServer? = null
    private val remoteChangePending = AtomicBoolean(false)

    /** The data directory the currently open [session] actually uses — a real local path for
     * [DbLocation.Local] (next to the `.mdb` file), [DbLocation.Nextcloud.dataDirectory] for a
     * Nextcloud session. Tracked explicitly rather than re-derived from [AppState.Ready.filePath]
     * (which holds the *remote* path for a Nextcloud session, not a real local path) — see
     * [agentConnectionsStore] and [readHistory], the two callers this used to break for Nextcloud. */
    private var currentDataDirectory: File? = null

    val journal = JournalPresenter()
    val operationForm = OperationFormPresenter()
    val saldo = SaldoPresenter()
    val filter = FilterPresenter()
    val dictionary = DictionaryPresenter()
    val rate = RatePresenter()

    private var quickFilterConditions: List<FilterCondition> = emptyList()

    /**
     * Commits [newFilter] from the «Фильтр» panel (§6.3, FR-26) and re-applies it to [journal],
     * merged with the quick filter's own conditions (§6.2 "Быстрый фильтр").
     */
    fun applyFilter(newFilter: JournalFilter) {
        filter.setFilter(newFilter)
        recomputeEffectiveFilter()
    }

    /**
     * Replaces the quick filter's conditions. A condition here overrides the main panel's for the
     * same field — there is no live X-Cash to check the original's exact precedence, so this is a
     * decided simplification (document and revisit at stage 0.6).
     */
    fun setQuickFilterConditions(conditions: List<FilterCondition>) {
        quickFilterConditions = conditions
        recomputeEffectiveFilter()
    }

    private fun recomputeEffectiveFilter() {
        val quickKeys = quickFilterConditions.map { it.fieldKey() }.toSet()
        val main = filter.state.value.filter.conditions.filter { it.fieldKey() !in quickKeys }
        journal.applyFilter(JournalFilter(main + quickFilterConditions))
    }

    @Synchronized
    fun openDatabase(file: File): OpenResult {
        closeDatabase()
        return try {
            val dataDir = dataDirectory(file)
            val sess = LocalStorageProvider.open(DbLocation.Local(file, dataDir))
            session = sess
            currentDataDirectory = dataDir
            val readOnly = sess.status is SyncStatus.ReadOnly
            // Only the write-lock holder may answer the channel (dev_plan.md §12.15, FR-AG6) — a
            // read-only session here means another instance already owns it and is (or should be)
            // listening itself; starting our own would just fight it for the same socket path.
            if (!readOnly) agentChannel = AgentChannelServer.start(file) { changeSet -> applyRemoteChangeSet(changeSet) }
            val dataset = sess.snapshot.toDataset()
            feedPresenters(dataset)
            settings.lastDatabasePath = file.absolutePath
            settings.addRecent(file.absolutePath)
            settings.lastLocationIsNextcloud = false
            _state.value = AppState.Ready(file.absolutePath, readOnly, dataset)
            OpenResult.Success(readOnly)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            _state.value = AppState.Error(msg)
            OpenResult.Failure(msg)
        }
    }

    @Synchronized
    fun closeDatabase() {
        agentChannel?.close()
        agentChannel = null
        session?.close()
        session = null
        currentDataDirectory = null
        _state.value = AppState.NoDatabase
    }

    /**
     * Device-local cache directory for one Nextcloud location (dev_plan.md §9.4: "для Nextcloud —
     * `oc:fileid` + адрес сервера"; simplified here to a hash of server+path — a stable `oc:fileid`
     * would need its own `PROPFIND` this doesn't bother with yet, and server+path is already unique
     * per database).
     */
    private fun nextcloudCacheDirectory(server: String, remotePath: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$server|$remotePath".toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(32)
        return settings.configDir.resolve("nextcloud-cache").resolve(hash).toFile()
    }

    /**
     * FR-S1 "Переместить базу в Nextcloud": uploads the currently open local database as the
     * initial server copy, then opens that same network location in its place. [credentials] are
     * remembered afterward ([rememberNextcloudLocation]) — [tryAutoOpenLastNextcloud] reopens the
     * same location without asking to log in again on a later restart.
     */
    @Synchronized
    fun moveToNextcloud(credentials: NextcloudCredentials, remotePath: String): OpenResult {
        val currentFile = (_state.value as? AppState.Ready)?.filePath?.let(::File)
            ?: return OpenResult.Failure("Нет открытой локальной базы")
        val location = DbLocation.Nextcloud(
            credentials.server, credentials.loginName, credentials.appPassword, remotePath,
            nextcloudCacheDirectory(credentials.server, remotePath),
        )
        return when (val move = NextcloudStorageProvider.moveLocalDatabaseToNextcloud(currentFile, location)) {
            is NextcloudStorageProvider.MoveResult.Moved -> openNextcloudLocation(location)
            is NextcloudStorageProvider.MoveResult.AlreadyExistsOnServer -> OpenResult.Failure("На сервере уже есть файл по этому пути")
            is NextcloudStorageProvider.MoveResult.Failed -> OpenResult.Failure(move.reason)
        }
    }

    /** FR-S2 "Открыть базу из Nextcloud" — on another device, or the first time this one sees this location. */
    @Synchronized
    fun openFromNextcloud(credentials: NextcloudCredentials, remotePath: String): OpenResult {
        val location = DbLocation.Nextcloud(
            credentials.server, credentials.loginName, credentials.appPassword, remotePath,
            nextcloudCacheDirectory(credentials.server, remotePath),
        )
        return openNextcloudLocation(location)
    }

    private fun openNextcloudLocation(location: DbLocation.Nextcloud): OpenResult {
        closeDatabase()
        return try {
            val sess = NextcloudStorageProvider.open(location)
            session = sess
            currentDataDirectory = location.dataDirectory
            val readOnly = sess.status is SyncStatus.ReadOnly
            val dataset = sess.snapshot.toDataset()
            feedPresenters(dataset)
            _state.value = AppState.Ready(location.remotePath, readOnly, dataset)
            rememberNextcloudLocation(location)
            OpenResult.Success(readOnly)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            _state.value = AppState.Error(msg)
            OpenResult.Failure(msg)
        }
    }

    /** Called once a Nextcloud session actually opens — covers [moveToNextcloud], [openFromNextcloud]
     * and [tryAutoOpenLastNextcloud]'s own reuse of a previous one alike, so a later restart finds
     * it again (dev_plan.md NFR-8's fix: the app password no longer lives in memory only). */
    private fun rememberNextcloudLocation(location: DbLocation.Nextcloud) {
        secretStore.put(secretKey(location.server, location.loginName), location.appPassword)
        settings.nextcloudServer = location.server
        settings.nextcloudLoginName = location.loginName
        settings.nextcloudRemotePath = location.remotePath
        settings.lastLocationIsNextcloud = true
    }

    private fun secretKey(server: String, loginName: String) = "nextcloud|$server|$loginName"

    /**
     * FR-S2 at startup ([Main.kt][kalita.app.desktop.MainKt]): if the last-opened location was
     * Nextcloud and [secretStore] still has its password, reopens it without asking the user to
     * log in again. Returns `null` (not attempted, not failed) when no Nextcloud location was
     * remembered or no password is available — the caller falls back to the existing
     * [AppSettings.lastDatabasePath] logic exactly as if this method didn't exist.
     */
    @Synchronized
    fun tryAutoOpenLastNextcloud(): OpenResult? {
        if (!settings.lastLocationIsNextcloud) return null
        val server = settings.nextcloudServer ?: return null
        val loginName = settings.nextcloudLoginName ?: return null
        val remotePath = settings.nextcloudRemotePath ?: return null
        val password = secretStore.get(secretKey(server, loginName)) ?: return null
        return openNextcloudLocation(DbLocation.Nextcloud(server, loginName, password, remotePath, nextcloudCacheDirectory(server, remotePath)))
    }

    @Synchronized
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

    /**
     * Applies a [ChangeSet] built by a `kalita` CLI/`kalita mcp` process talking through the local
     * channel (dev_plan.md §12.15, FR-AG6: [kalita.storage.local.AgentChannelServer] calls this as
     * its handler). Runs on that process's own connection thread, not the Qt thread — only touches
     * plain Kotlin state here ([session], the presenters, [_state]), never a `QWidget` directly;
     * [MainWindow] notices [consumeRemoteChange] on its own poll timer (the Qt thread) and repaints
     * from there, the same way [onRefresh][kalita.app.desktop.MainWindow] already does after F5.
     */
    @Synchronized
    fun applyRemoteChangeSet(changeSet: ChangeSet): SubmitResult {
        val sess = session ?: return SubmitResult.Failed("No database open")
        val result = sess.submit(changeSet)
        if (result is SubmitResult.Applied) {
            val dataset = sess.snapshot.toDataset()
            feedPresenters(dataset)
            val readOnly = sess.status is SyncStatus.ReadOnly
            _state.value = AppState.Ready(settings.lastDatabasePath ?: "", readOnly, dataset)
            remoteChangePending.set(true)
        }
        return result
    }

    /** Polled by [MainWindow] (the Qt thread); true at most once per remotely-applied change set. */
    fun consumeRemoteChange(): Boolean = remoteChangePending.getAndSet(false)

    /**
     * The currently-open database's agent connection settings (dev_plan.md §12.14/§12.15, FR-AG5,
     * backing [AgentConnectionsDialog]) — `null` when no database is open, same as every other
     * per-database accessor here.
     */
    fun agentConnectionsStore(): AgentConnectionStore? {
        val dir = currentDataDirectory ?: return null
        return AgentConnectionStore(dir)
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

    // --- Operations (FR-20…FR-22) ---

    fun addOperation(draft: OperationDraft): SubmitResult =
        submitChange("Добавить операцию") { builder ->
            builder.insert(Table.Operations, builder.nextTempKey(), draftToColumns(draft))
        }

    fun updateOperation(id: Long, draft: OperationDraft): SubmitResult =
        submitChange("Изменить операцию") { builder ->
            builder.update(Table.Operations, id, draftToColumns(draft))
        }

    fun softDeleteOperations(ids: List<Long>): SubmitResult =
        submitChange("Удалить операции (${ids.size})") { builder ->
            for (id in ids) builder.softDelete(Table.Operations, id)
        }

    fun restoreOperations(ids: List<Long>): SubmitResult =
        submitChange("Восстановить операции (${ids.size})") { builder ->
            for (id in ids) builder.restore(Table.Operations, id)
        }

    private fun draftToColumns(draft: OperationDraft): Map<String, Any?> = buildMap {
        put("Date", draft.date?.atStartOfDay())
        put("Summa", draft.amount)
        put("Currency_ID", draft.currencyId)
        put("GroupBudget_ID", draft.budgetItemId)
        put("TypeOper_ID", draft.typeId)
        put("Comment", draft.comment)
        put("People_ID", draft.counterpartyId)
        put("AccountFrom", draft.accountFrom)
        put("AccountTo", draft.accountTo)
    }

    /** Read history entries from the data directory's history.jsonl (FR-7). Nextcloud sessions
     * have no `history.jsonl` yet (dev_plan.md §10 doesn't give `sync` its own `ChangeHistory` —
     * a separate, later gap) — [currentDataDirectory] still points at the right folder for one,
     * `ChangeHistory.read()` on a file that doesn't exist there just answers an empty list. */
    fun readHistory(): List<kalita.storage.local.HistoryEntry> {
        val dir = currentDataDirectory ?: return emptyList()
        val historyFile = File(dir, "history.jsonl")
        return kalita.storage.local.ChangeHistory(historyFile).read()
    }

    // --- Internal ---

    private fun currentDataset(): Dataset? = (_state.value as? AppState.Ready)?.dataset

    @Synchronized
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

    private fun dataDirectory(dbFile: File): File = kalita.storage.local.defaultDataDirectory(dbFile)
}
