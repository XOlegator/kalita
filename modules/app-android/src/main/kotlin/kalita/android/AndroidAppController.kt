// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.agent.api.HttpVisionTransport
import kalita.agent.api.AgentSession
import kalita.agent.api.ListInboxTool
import kalita.agent.api.ApproveInboxTool
import kalita.agent.api.RejectInboxTool
import kalita.agent.api.InboxRecovery
import kalita.agent.api.ReceiptImage
import kalita.agent.api.ReceiptProposal
import kalita.agent.api.ReceiptPrompt
import kalita.agent.api.ReceiptRecognizer
import kalita.agent.api.RecognitionException
import kalita.agent.api.RecognizerConfig
import kalita.agent.api.VisionProvider
import kalita.agent.api.VisionTransport
import kalita.agent.api.ReceiptProposalStore
import kalita.agent.api.ReceiptReviewModel
import kalita.core.AuditStamp
import kalita.core.Dataset
import kalita.core.CurrencyCatalog
import kalita.plugin.api.PluginHost
import kalita.plugin.api.PluginHttpClient
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import kalita.plugin.host.PluginHostServices
import kalita.plugin.host.RateLoadResult
import kalita.plugin.host.RateLoader
import kalita.plugin.host.RateLookup
import kalita.plugin.ratescbr.CbrRatesPlugin
import kalita.plugin.api.BankTransaction
import kalita.plugin.reconcile.BankMappingStore
import kalita.plugin.reconcile.BankTransactionInbox
import kalita.plugin.reconcile.CategoryRuleStore
import kalita.plugin.reconcile.CategorizerChain
import kalita.plugin.reconcile.Decision
import kalita.plugin.reconcile.HistoryCategorizer
import kalita.plugin.reconcile.ReconcileWriter
import kalita.plugin.reconcile.Reconciler
import kalita.plugin.reconcile.ReconLinkStore
import kalita.plugin.reconcile.RuleCategorizer
import kalita.plugin.reconcile.inboxKey
import kalita.plugin.reconcile.key
import kalita.presentation.Strings
import java.time.LocalDate
import kalita.core.OperationDraft
import kalita.platform.NoopSecretStore
import kalita.platform.SecretStore
import kalita.presentation.DictionaryPresenter
import kalita.presentation.JournalPresenter
import kalita.presentation.OperationFormPresenter
import kalita.presentation.SaldoPresenter
import kalita.storage.api.ChangeSetBuilder
import kalita.storage.api.DbLocation
import kalita.storage.api.StorageSession
import kalita.storage.api.SubmitResult
import kalita.storage.api.SyncStatus
import kalita.storage.api.Table
import kalita.storage.api.toOperationColumns
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.DatabaseSettings
import kalita.storage.local.defaultDataDirectory
import kalita.storage.local.toDataset
import kalita.sync.NextcloudCredentials
import kalita.sync.NextcloudStorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.Instant

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
 * backups and history as the other. [kalita.storage.local.LocalDatabase] reports
 * [SyncStatus.ReadOnly] by itself whenever a write lock is already held elsewhere, same as the
 * desktop app — every write method below (FR-A3/A4) refuses on that status instead of needing its
 * own check.
 *
 * FR-A1 also asks for export back out and for "the last database opens at launch" — there is
 * only ever one local copy ([databaseFile], fixed at `filesDir/Data.mdb`), so "last database"
 * needs no separate bookkeeping (no `SharedPreferences`, unlike the desktop app's
 * `AppSettings.lastDatabasePath`): launch just has to check whether that one file is already
 * there. Reopening it is safe across process restarts — [kalita.storage.local.DatabaseLock]
 * releases its OS [java.nio.channels.FileLock] on close but deliberately leaves the lock file
 * itself on disk, so a fresh process can re-acquire it without issue.
 */
class AndroidAppController(
    private val filesDir: File,
    private val secretStore: SecretStore = NoopSecretStore,
    /** Network for the rate source (FR-R12); tests pass a canned one. */
    private val rateHttpClient: PluginHttpClient = AndroidPluginHttpClient(),
) {
    /** The one built-in rate source. Android has no plugin registry yet — nothing here can be switched off. */
    private val rateSource = CbrRatesPlugin()

    private val _state = MutableStateFlow<AndroidAppState>(AndroidAppState.NoDatabase)
    val state: StateFlow<AndroidAppState> = _state.asStateFlow()

    /** FR-A7: where the open database stands against the server; `null` while none is open. Only the
     * Nextcloud states ([SyncStatus.Synced]/[SyncStatus.Pending]/[SyncStatus.Offline]/[SyncStatus.Conflicted])
     * are worth showing, see [syncStatusText]. */
    private val _syncStatus = MutableStateFlow<SyncStatus?>(null)
    val syncStatus: StateFlow<SyncStatus?> = _syncStatus.asStateFlow()

    val journal = JournalPresenter()
    val operationForm = OperationFormPresenter()
    val saldo = SaldoPresenter()
    val dictionary = DictionaryPresenter()

    private var session: StorageSession? = null
    private var agentDataDirectory: File? = null

    private val databaseFile: File get() = File(filesDir, "Data.mdb")

    @Synchronized
    private fun <T> withAgentSession(block: (AgentSession) -> T): T {
        val sess = checkNotNull(session) { "Нет открытой базы" }
        val directory = checkNotNull(agentDataDirectory) { "Нет каталога входящих" }
        return AgentSession.borrow(sess, directory).use(block)
    }

    fun agentInbox(): Pair<JsonObject, JsonObject> = withAgentSession {
        ListInboxTool(it).execute() to InboxRecovery(it).list()
    }

    @Synchronized
    fun processAgentInbox(id: String, action: String): JsonObject {
        val sess = checkNotNull(session) { "Нет открытой базы" }
        check(sess.status !is SyncStatus.ReadOnly) { "База открыта только для чтения" }
        try {
            return withAgentSession { agent ->
                when (action) {
                    "approve" -> ApproveInboxTool(agent).execute(id)
                    "reject" -> RejectInboxTool(agent).execute(id)
                    "committed" -> InboxRecovery(agent).resolve(id, true)
                    "notCommitted" -> InboxRecovery(agent).resolve(id, false)
                    else -> error("Неизвестное действие")
                }
            }
        } finally {
            publish(sess)
        }
    }

    fun bankMappings(): BankMappingStore? = session?.let { BankMappingStore(defaultDataDirectory(databaseFile)) }
    fun categoryRules(): CategoryRuleStore? = session?.let { CategoryRuleStore(defaultDataDirectory(databaseFile)) }
    private fun reconLinks(): ReconLinkStore? = session?.let { ReconLinkStore(defaultDataDirectory(databaseFile)) }

    @Synchronized
    fun queueStatement(transactions: List<BankTransaction>): List<BankTransaction> {
        checkNotNull(session) { "Нет открытой базы" }
        val inbox = BankTransactionInbox(defaultDataDirectory(databaseFile))
        val linked = checkNotNull(reconLinks()).linkedBankTransactions()
        inbox.add(transactions.filter { it.key !in linked })
        val keys = transactions.map { it.inboxKey() }.toSet()
        return inbox.read().filter { it.inboxKey() in keys && it.key !in linked }
    }

    @Synchronized
    fun pendingStatements(): List<BankTransaction> {
        checkNotNull(session) { "Нет открытой базы" }
        val linked = checkNotNull(reconLinks()).linkedBankTransactions()
        return BankTransactionInbox(defaultDataDirectory(databaseFile)).read().filter { it.key !in linked }
    }

    suspend fun reconcile(transactions: List<BankTransaction>, accountId: Long, currencyId: Long): kalita.plugin.reconcile.ReconciliationReport {
        val dataset = (state.value as AndroidAppState.Ready).dataset
        val links = reconLinks()
        val categorizer = CategorizerChain(listOf(RuleCategorizer(categoryRules()?.read().orEmpty(), dataset), HistoryCategorizer(dataset)))
        return Reconciler(dataset, categorizer = categorizer).reconcile(
            transactions, accountId, currencyId, links?.linkedBankTransactions().orEmpty(), links?.linkedRows().orEmpty(),
        )
    }

    @Synchronized
    fun applyImport(decisions: List<Decision>, accountId: Long): SubmitResult {
        val sess = session ?: return SubmitResult.Failed("Нет открытой базы")
        if (sess.status is SyncStatus.ReadOnly) return SubmitResult.Failed("База открыта только для чтения")
        if (decisions.isEmpty()) return SubmitResult.Applied(emptyMap())
        val directory = defaultDataDirectory(databaseFile)
        val guard = kalita.plugin.reconcile.ImportGuard(directory)
        try {
            when (guard.recover { id ->
                if (sess.status != SyncStatus.Local) null else
                    kalita.storage.local.ChangeHistory(File(directory, "history.jsonl")).read()
                        .singleOrNull { it.changeSet.id == id }?.tempKeys
            }) {
                kalita.plugin.reconcile.ImportGuard.Recovery.RECOVERED -> return SubmitResult.Failed("Предыдущий импорт восстановлен. Обновите сверку.")
                kalita.plugin.reconcile.ImportGuard.Recovery.BUSY, kalita.plugin.reconcile.ImportGuard.Recovery.UNCONFIRMED ->
                    return SubmitResult.Failed("Предыдущий импорт не завершён или выполняется. Проверьте журнал и банковские связи перед восстановлением.")
                kalita.plugin.reconcile.ImportGuard.Recovery.NO_MARKER -> Unit
            }
        } catch (e: Exception) {
            return SubmitResult.Failed("Не удалось восстановить импорт; проверьте журнал и банковские связи: ${e.message ?: e}")
        }
        val dataset = (state.value as? AndroidAppState.Ready)?.dataset ?: return SubmitResult.Failed("Нет открытой базы")
        if (decisions.filterIsInstance<Decision.Create>().any { kalita.core.OperationRules(dataset).validate(it.draft).isNotEmpty() }) {
            return SubmitResult.Failed("Не заполнены обязательные поля новых операций")
        }
        val rows = decisions.flatMap {
            when (it) {
                is Decision.Link -> it.rows.map { row -> row.id }
                is Decision.MoveToAccount -> listOf(it.operation.id)
                else -> emptyList()
            }
        }
        if (rows.size != rows.distinct().size) return SubmitResult.Failed("Одна операция выбрана для нескольких строк выписки")
        val builder = ChangeSetBuilder(sess.snapshot, AuditStamp(userId = sess.snapshot.auditUserId ?: 1, timestamp = LocalDateTime.now()), "android")
        val prepared = ReconcileWriter.prepare(builder, decisions, accountId)
        val changeSet = if (prepared.isEmpty) null else builder.build("Импорт выписки")
        val at = Instant.now()
        try {
            if (!guard.begin(prepared, changeSet?.id, at, "android")) return SubmitResult.Failed("Предыдущий импорт не завершён или выполняется. Проверьте журнал и банковские связи перед восстановлением.")
            val keys = decisions.map { it.proposal.transaction.key }
            val linkStore = checkNotNull(reconLinks())
            if (keys.distinct().size != keys.size || keys.any { it in linkStore.linkedBankTransactions() }) {
                guard.finish()
                return SubmitResult.Failed("Строки выписки уже обработаны или повторяются. Обновите сверку.")
            }
            val result = if (prepared.isEmpty) SubmitResult.Applied(emptyMap()) else sess.submit(checkNotNull(changeSet))
            if (result is SubmitResult.Applied) {
                val links = prepared.links(result.tempKeys, at, "android")
                check(links.size == decisions.size) { "Не удалось определить все созданные операции" }
                linkStore.append(links)
                guard.finish()
                if (!prepared.isEmpty) publish(sess)
                val written = links.map { it.bankTx }.toSet()
                BankTransactionInbox(defaultDataDirectory(databaseFile)).remove(
                    decisions.map { it.proposal.transaction }.filter { it.key in written }.map { it.inboxKey() }.toSet(),
                )
                return result
            }
            return SubmitResult.Failed("Результат импорта: $result. Проверьте журнал и банковские связи перед восстановлением.")
        } catch (e: Exception) {
            return SubmitResult.Failed("Импорт прерван; проверьте журнал и банковские связи: ${e.message ?: e}")
        } finally {
            guard.close()
        }
    }

    /** Non-secret bookkeeping for "which Nextcloud location opens at launch" (FR-S2's half of
     * "the last database opens at launch", dev_plan.md §9.4) — the app password itself never goes
     * here, only into [secretStore]. Deliberately a plain file next to `Data.mdb`, not Android
     * `SharedPreferences`: [AndroidAppController] takes no [android.content.Context] at all (see
     * the class doc comment on plain-JVM testability), and this needs nothing SharedPreferences
     * would give that a JSON file in the already-available [filesDir] doesn't. */
    private val nextcloudLocationFile: File get() = File(filesDir, "nextcloud-location.json")

    /** If a remembered Nextcloud location's password is still in [secretStore], reopens it
     * (FR-S2); otherwise, if `Data.mdb` is already there from a previous launch, opens that
     * instead; otherwise does nothing. `suspend`/[Dispatchers.IO] — reopening a Nextcloud location
     * can hit the network (a missing/stale local cache falls back to a real `GET`), and Android
     * throws [android.os.NetworkOnMainThreadException] for that on the calling thread outright;
     * found live, the hard way, from the UI-triggered [moveToNextcloud]/[openFromNextcloud] below,
     * which share this same exposure. */
    suspend fun openIfExists() = withContext(Dispatchers.IO) {
        val remembered = readNextcloudLocation()
        if (remembered != null) {
            val password = secretStore.get(secretKey(remembered.server, remembered.loginName))
            if (password != null) {
                openNextcloudLocation(
                    DbLocation.Nextcloud(
                        remembered.server, remembered.loginName, password, remembered.remotePath,
                        nextcloudCacheDirectory(remembered.server, remembered.remotePath),
                    ),
                )
                return@withContext
            }
        }
        if (databaseFile.isFile) {
            openPrivateFile()
        }
    }

    /** Copies [input] into [filesDir] as `Data.mdb`, replacing whatever was open before — an
     * explicit switch back to local, so any remembered Nextcloud location stops shadowing it. */
    fun importAndOpen(input: InputStream) {
        closeDatabase()
        try {
            databaseFile.outputStream().use { out -> input.copyTo(out) }
            nextcloudLocationFile.delete()
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

    @Synchronized
    private fun openPrivateFile() {
        try {
            val sess = LocalStorageProvider.open(DbLocation.Local(databaseFile, defaultDataDirectory(databaseFile)))
            session = sess
            agentDataDirectory = defaultDataDirectory(databaseFile)
            publish(sess)
        } catch (e: Exception) {
            _state.value = AndroidAppState.Error(e.message ?: e.toString())
        }
    }

    /** Feeds every presenter from [sess]'s snapshot and publishes a fresh [AndroidAppState.Ready] and [syncStatus]. */
    private fun publish(sess: StorageSession) {
        val dataset = sess.snapshot.toDataset(defaultDataDirectory(databaseFile))
        journal.load(dataset)
        operationForm.load(dataset)
        saldo.load(dataset)
        dictionary.load(dataset)
        _syncStatus.value = sess.status
        _state.value = AndroidAppState.Ready(sess.status is SyncStatus.ReadOnly, dataset)
    }

    /**
     * FR-A7 «Синхронизировать»: one §10.2 round trip — push the queue, pull what is newer, rebase on
     * conflict ([StorageSession.refresh]). Local-only databases have nothing to sync. `suspend`/
     * [Dispatchers.IO]: a real network call, which Android refuses on the main thread. A changed
     * snapshot is re-published, so the journal shows what the other device wrote.
     */
    suspend fun syncNow(): SyncStatus? = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext null
        if (sess.status is SyncStatus.Local || sess.status is SyncStatus.ReadOnly) return@withContext sess.status
        val changed = try {
            sess.refresh()
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Offline(e.message ?: e.javaClass.simpleName)
            return@withContext _syncStatus.value
        }
        if (changed) publish(sess) else _syncStatus.value = sess.status
        sess.status
    }

    /** Same hashing scheme as the desktop `AppController`'s own `nextcloudCacheDirectory` (dev_plan.md §9.4). */
    private fun nextcloudCacheDirectory(server: String, remotePath: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$server|$remotePath".toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(32)
        return File(File(filesDir, "nextcloud-cache"), hash)
    }

    /**
     * FR-S1 "Переместить базу в Nextcloud": uploads the currently open local `Data.mdb` as the
     * initial server copy, then opens that same network location in its place. [credentials] are
     * remembered afterward ([rememberNextcloudLocation]) — a later [openIfExists] (app restart)
     * reopens the same location without asking to log in again. `suspend`/[Dispatchers.IO] — see
     * [openIfExists]'s own doc comment: this is a blocking network call, and the UI calls this
     * directly from a button's click handler, squarely on the main thread otherwise.
     */
    suspend fun moveToNextcloud(credentials: NextcloudCredentials, remotePath: String): SubmitResult = withContext(Dispatchers.IO) {
        if (!databaseFile.isFile) return@withContext SubmitResult.Failed("Нет открытой локальной базы")
        val location = kalita.storage.api.DbLocation.Nextcloud(
            credentials.server, credentials.loginName, credentials.appPassword, remotePath,
            nextcloudCacheDirectory(credentials.server, remotePath),
        )
        when (val move = NextcloudStorageProvider.moveLocalDatabaseToNextcloud(databaseFile, location)) {
            is NextcloudStorageProvider.MoveResult.Moved -> {
                openNextcloudLocation(location)
                SubmitResult.Applied(emptyMap())
            }
            is NextcloudStorageProvider.MoveResult.AlreadyExistsOnServer -> SubmitResult.Failed("На сервере уже есть файл по этому пути")
            is NextcloudStorageProvider.MoveResult.Failed -> SubmitResult.Failed(move.reason)
        }
    }

    /** FR-S2 "Открыть базу из Nextcloud" — on another device, or the first time this one sees this
     * location. `suspend`/[Dispatchers.IO] — see [openIfExists]'s own doc comment. */
    suspend fun openFromNextcloud(credentials: NextcloudCredentials, remotePath: String) = withContext(Dispatchers.IO) {
        val location = kalita.storage.api.DbLocation.Nextcloud(
            credentials.server, credentials.loginName, credentials.appPassword, remotePath,
            nextcloudCacheDirectory(credentials.server, remotePath),
        )
        openNextcloudLocation(location)
    }

    @Synchronized
    private fun openNextcloudLocation(location: kalita.storage.api.DbLocation.Nextcloud) {
        closeDatabase()
        try {
            val sess = NextcloudStorageProvider.open(location)
            session = sess
            agentDataDirectory = location.dataDirectory
            publish(sess)
            rememberNextcloudLocation(location)
        } catch (e: Exception) {
            _state.value = AndroidAppState.Error(e.message ?: e.toString())
        }
    }

    private data class RememberedNextcloudLocation(val server: String, val loginName: String, val remotePath: String)

    /** Called once a Nextcloud session actually opens — covers [moveToNextcloud], [openFromNextcloud]
     * and [openIfExists]'s own reuse of a previous one alike, so a later launch can find it again.
     * [SecretStore] key scheme matches the desktop `AppController`'s own (`secretKey`) — not load-
     * bearing (each platform's store is separate), kept identical only for readability. */
    private fun rememberNextcloudLocation(location: kalita.storage.api.DbLocation.Nextcloud) {
        secretStore.put(secretKey(location.server, location.loginName), location.appPassword)
        nextcloudLocationFile.writeText(
            buildJsonObject {
                put("server", location.server)
                put("loginName", location.loginName)
                put("remotePath", location.remotePath)
            }.toString(),
        )
    }

    private fun readNextcloudLocation(): RememberedNextcloudLocation? {
        if (!nextcloudLocationFile.isFile) return null
        return try {
            val o = Json.parseToJsonElement(nextcloudLocationFile.readText()).jsonObject
            RememberedNextcloudLocation(
                server = o.getValue("server").jsonPrimitive.content,
                loginName = o.getValue("loginName").jsonPrimitive.content,
                remotePath = o.getValue("remotePath").jsonPrimitive.content,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun secretKey(server: String, loginName: String) = "nextcloud|$server|$loginName"

    @Synchronized
    fun closeDatabase() {
        session?.close()
        session = null
        agentDataDirectory = null
        _syncStatus.value = null
        _state.value = AndroidAppState.NoDatabase
    }

    // --- Operations (FR-A3/A4's local half — write path, mirrors AppController 1:1) ---

    /** Adds a new operation from [draft]. Same [ChangeSetBuilder]/audit path as the desktop app
     * ([kalita.app.desktop.AppController.addOperation]), device tag `"android"` instead of
     * `"desktop"` — so `history.jsonl`/`Editor` tell the two apart (dev_plan.md §10.6). */
    fun addOperation(draft: OperationDraft): SubmitResult =
        submitChange("Добавить операцию") { builder ->
            builder.insert(Table.Operations, builder.nextTempKey(), draftToColumns(draft))
        }

    /** FR-A4: overwrites an existing operation's fields in place — [id] stays the same row. */
    fun updateOperation(id: Long, draft: OperationDraft): SubmitResult =
        submitChange("Изменить операцию") { builder -> builder.update(Table.Operations, id, draftToColumns(draft)) }

    /** FR-A4: soft-deletes — [kalita.storage.api.ChangeSetBuilder.softDelete], not a hard delete,
     * same as the desktop app and reversible via [restoreOperations]. */
    fun softDeleteOperations(ids: List<Long>): SubmitResult =
        submitChange("Удалить операции (${ids.size})") { builder ->
            for (id in ids) builder.softDelete(Table.Operations, id)
        }

    /** FR-A4: undoes [softDeleteOperations]. */
    fun restoreOperations(ids: List<Long>): SubmitResult =
        submitChange("Восстановить операции (${ids.size})") { builder ->
            for (id in ids) builder.restore(Table.Operations, id)
        }

    // --- Dictionaries (FR-A6, dev_plan.md Этап 10): view/add/edit/soft-delete, mirrors the
    // desktop AppController's dictionary CRUD 1:1 (kalita.app.desktop.AppController). The budget
    // item tree's moveUp/moveDown has no Android equivalent — FR-A6 explicitly cuts reordering
    // ("перестановки делаются на компьютере"), so only the tree-shaped add/edit/delete below exist.

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

    /**
     * Adds a currency with a code (derived from the name when [cbId] is blank — a rate source finds
     * the currency by it) and, when [rate] is given, its first `Course` row on [rateDate], in the
     * same change set — mirrors the desktop controller's `addCurrency`.
     */
    fun addCurrency(name: String, cbId: String?, rate: Double? = null, rateDate: LocalDate = LocalDate.now()): SubmitResult =
        submitChange("Добавить валюту «$name»") { builder ->
            val key = builder.nextTempKey()
            builder.insert(Table.Currencies, key, mapOf("Name" to name, "CB_ID" to CurrencyCatalog.codeOrSuggestion(name, cbId)))
            if (rate != null && rate > 0.0) {
                builder.insert(Table.Rates, builder.nextTempKey(), mapOf("Currency_ID" to key, "Date" to rateDate.atStartOfDay(), "Course" to rate))
            }
        }

    // --- Rates from the Internet (dev_plan.md FR-R12) ---

    private fun rateHost(): PluginHost =
        PluginHostServices(rateHttpClient, NoPluginSecrets, PluginLogger { _, _ -> }, database = { null }).forPlugin(rateSource.descriptor.id)

    /** The rate in force on [date] for a currency not added yet («Из Интернета» in the add form). Never throws. */
    suspend fun lookupNewCurrencyRate(name: String, cbId: String?, date: LocalDate = LocalDate.now()): RateLookup {
        val dataset = session?.snapshot?.toDataset(defaultDataDirectory(databaseFile)) ?: return RateLookup.Unavailable("Нет открытой базы")
        return withContext(Dispatchers.IO) { RateLoader.quoteForNewCurrency(rateSource, rateHost(), dataset, name, cbId, date) }
    }

    /**
     * «Курсы из Интернета»: fetches the rates of [from]..[to] for every currency and writes the new
     * and changed ones as one change set. Returns a line for the user either way.
     */
    suspend fun loadRates(from: LocalDate = LocalDate.now(), to: LocalDate = from): String {
        val dataset = session?.snapshot?.toDataset(defaultDataDirectory(databaseFile)) ?: return "Нет открытой базы"
        val result = try {
            withContext(Dispatchers.IO) { RateLoader.plan(rateSource, rateHost(), dataset, from, to) }
        } catch (e: Exception) {
            return Strings.rateLoadFailed(rateSource.sourceName, e.message ?: e.toString())
        }
        return when (result) {
            is RateLoadResult.Refused -> result.reason
            is RateLoadResult.Plan -> {
                val unmatched = if (result.unmatched.isEmpty()) "" else "\n${Strings.RATE_LOAD_UNMATCHED}: ${result.unmatched.joinToString { it.name ?: "id:${it.id}" }}"
                if (result.isEmpty) return "${Strings.RATE_LOAD_NOTHING_NEW}$unmatched"
                when (val submitted = submitChange("Загрузка курсов валют") { result.applyTo(it) }) {
                    is SubmitResult.Applied -> Strings.rateLoadDone(result.inserts.size, result.updates.size, result.unchanged) + unmatched
                    is SubmitResult.Conflicts -> "Не записано: данные изменены извне, повторите загрузку."
                    is SubmitResult.Failed -> "Не записано: ${submitted.reason}"
                }
            }
        }
    }

    /** The name of the rate source, for the load dialog's caption. */
    val rateSourceName: String get() = rateSource.sourceName

    // --- Base currency (kept outside the database, see DatabaseSettings) ---

    /** Sets the base currency for this database ([currencyId]) or returns it to automatic detection (`null`). */
    fun setBaseCurrency(currencyId: Long?) {
        val sess = session ?: return
        DatabaseSettings(defaultDataDirectory(databaseFile)).baseCurrencyId = currencyId
        publish(sess)
    }

    private object NoPluginSecrets : PluginSecretStore {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
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

    fun addBudgetItem(parentId: Long?, name: String): SubmitResult = submitChange("Добавить статью «$name»") { builder ->
        builder.insert(Table.BudgetItems, builder.nextTempKey(), mapOf("Name" to name, "Parent_ID" to parentId))
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

    // --- Receipts for review (dev_plan.md §12.19), mirrors the desktop AppController 1:1 ---

    /** Proposals live next to the local database copy, outside `Data.mdb` — also when it is a Nextcloud one. */
    private val receiptStore: ReceiptProposalStore get() = ReceiptProposalStore(defaultDataDirectory(databaseFile))

    /** Receipts waiting for a decision, oldest first. */
    fun pendingReceipts(): List<ReceiptProposal> = receiptStore.pending()

    internal fun receiptFrames(proposal: ReceiptProposal): List<File> =
        ReceiptFrameStore(defaultDataDirectory(databaseFile)).frames(proposal.id)

    /**
     * Brings in a `receipt-proposals/<id>.json` made by an agent on another machine (the phone runs no
     * agent itself). `null` when [input] is not a proposal file.
     */
    fun importReceipt(input: InputStream): ReceiptProposal? =
        receiptStore.import(input.readBytes().toString(Charsets.UTF_8))

    // --- Receipt photos → vision model → the same review window (dev_plan.md Срез 17) ---

    /** Network seam of [recognizeReceipt]; tests replace it. */
    internal var visionTransport: VisionTransport = HttpVisionTransport
    internal var beforeReceiptPublication: () -> Unit = {}

    /** The saved recognition settings; `null` until a key has been entered. */
    fun recognizerConfig(): RecognizerConfig? {
        val provider = secretStore.get(RECOGNIZER_PROVIDER)?.let { n -> VisionProvider.entries.firstOrNull { it.name == n } } ?: return null
        val key = secretStore.get(RECOGNIZER_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return RecognizerConfig(
            provider,
            secretStore.get(RECOGNIZER_URL)?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl,
            secretStore.get(RECOGNIZER_MODEL)?.takeIf { it.isNotBlank() } ?: provider.defaultModel,
            key,
        )
    }

    fun saveRecognizerConfig(config: RecognizerConfig) {
        secretStore.put(RECOGNIZER_PROVIDER, config.provider.name)
        secretStore.put(RECOGNIZER_URL, config.baseUrl.trim())
        secretStore.put(RECOGNIZER_MODEL, config.model.trim())
        secretStore.put(RECOGNIZER_KEY, config.apiKey.trim())
    }

    /**
     * Sends the photographed receipt [images] to the configured vision model and stores the answer as a
     * pending proposal — the review window then opens on it like on any agent's proposal. Nothing is written
     * to the database here. The failure text is meant to be shown to the person as it is.
     */
    suspend fun recognizeReceipt(images: List<ReceiptImage>): Result<ReceiptProposal> = withContext(Dispatchers.IO) {
        val config = recognizerConfig() ?: return@withContext Result.failure(RecognitionException("Распознавание не настроено — укажите ключ в настройках"))
        val dataset = (state.value as? AndroidAppState.Ready)?.dataset ?: return@withContext Result.failure(RecognitionException("Нет открытой базы"))
        val context = currentCoroutineContext()
        runCatching {
            val directory = defaultDataDirectory(databaseFile)
            val transport = VisionTransport { url, headers, body ->
                context.ensureActive()
                visionTransport.post(url, headers, body).also { context.ensureActive() }
            }
            runInterruptible { receiptRecognizer(directory, images, context::ensureActive, transport).recognize(images, dataset, directory, config, "android") }
        }
            .also { if (it.exceptionOrNull() is CancellationException) throw it.exceptionOrNull()!! }
            .recoverCatching { if (it is RecognitionException) throw it else throw RecognitionException(it.message ?: it.javaClass.simpleName, it) }
    }

    /** The instruction to paste into a chat app (with the photos attached there) — the no-key path. */
    fun manualReceiptPrompt(imageCount: Int): String? =
        (state.value as? AndroidAppState.Ready)?.dataset?.let { ReceiptPrompt.manual(it, imageCount) }

    /** The model's pasted answer → a pending proposal, exactly as if the service had been called. */
    suspend fun importRecognizedReply(text: String, images: List<ReceiptImage> = emptyList()): Result<ReceiptProposal> = withContext(Dispatchers.IO) {
        val dataset = (state.value as? AndroidAppState.Ready)?.dataset ?: return@withContext Result.failure(RecognitionException("Нет открытой базы"))
        val context = currentCoroutineContext()
        runCatching {
            val directory = defaultDataDirectory(databaseFile)
            receiptRecognizer(directory, images, context::ensureActive).importReply(text, dataset, directory, "android")
        }
            .also { if (it.exceptionOrNull() is CancellationException) throw it.exceptionOrNull()!! }
            .recoverCatching { if (it is RecognitionException) throw it else throw RecognitionException(it.message ?: it.javaClass.simpleName, it) }
    }

    private fun receiptRecognizer(
        directory: File,
        images: List<ReceiptImage>,
        ensureActive: () -> Unit,
        transport: VisionTransport = HttpVisionTransport,
    ) = ReceiptRecognizer(transport) { store, proposal ->
        val frames = ReceiptFrameStore(directory)
        ensureActive()
        frames.save(proposal.id, images)
        try {
            beforeReceiptPublication()
            ensureActive()
            store.addNew(proposal)
        } catch (e: Throwable) {
            if (images.isNotEmpty()) frames.delete(proposal.id)
            throw e
        }
    }

    /** «Сохранить в базу»: the model's operations as **one** change set, then the decision and what it teaches. */
    fun saveReceipt(model: ReceiptReviewModel): SubmitResult {
        val problems = model.problems()
        if (problems.isNotEmpty()) return SubmitResult.Failed(problems.joinToString("\n"))
        val drafts = model.drafts()
        val title = model.merchant?.trim()?.takeIf { it.isNotEmpty() } ?: "без названия"
        var changeSetId: String? = null
        val result = submitChange("Чек: $title (${drafts.size})", onBuilt = { changeSetId = it.id.toString() }) { builder ->
            for (draft in drafts) builder.insert(Table.Operations, builder.nextTempKey(), draft.toOperationColumns())
        }
        if (result is SubmitResult.Applied) {
            val dataset = (state.value as? AndroidAppState.Ready)?.dataset ?: Dataset.EMPTY
            receiptStore.complete(model.proposal, model.resolution(changeSetId, drafts.size), dataset)
        }
        return result
    }

    /** «Отклонить»: nothing is written to the database. */
    fun rejectReceipt(proposal: ReceiptProposal, reason: String?) {
        val dataset = (state.value as? AndroidAppState.Ready)?.dataset ?: Dataset.EMPTY
        receiptStore.reject(proposal, reason?.trim()?.ifEmpty { null }, dataset)
    }

    private fun draftToColumns(draft: OperationDraft): Map<String, Any?> = mapOf(
        "Date" to draft.date?.atStartOfDay(),
        "Summa" to draft.amount,
        "Currency_ID" to draft.currencyId,
        "GroupBudget_ID" to draft.budgetItemId,
        "TypeOper_ID" to draft.typeId,
        "Comment" to draft.comment,
        "People_ID" to draft.counterpartyId,
        "AccountFrom" to draft.accountFrom,
        "AccountTo" to draft.accountTo,
    )

    private fun submitChange(
        description: String,
        onBuilt: (kalita.storage.api.ChangeSet) -> Unit = {},
        block: (ChangeSetBuilder) -> Unit,
    ): SubmitResult {
        val sess = session ?: return SubmitResult.Failed("Нет открытой базы")
        if (sess.status is SyncStatus.ReadOnly) return SubmitResult.Failed("База открыта только для чтения")
        val audit = AuditStamp(userId = sess.snapshot.auditUserId ?: 1, timestamp = LocalDateTime.now())
        val builder = ChangeSetBuilder(sess.snapshot, audit, "android")
        block(builder)
        val changeSet = builder.build(description)
        onBuilt(changeSet)
        val result = sess.submit(changeSet)
        if (result is SubmitResult.Applied) publish(sess)
        return result
    }

    private companion object {
        const val RECOGNIZER_PROVIDER = "receipt-ai|provider"
        const val RECOGNIZER_URL = "receipt-ai|baseUrl"
        const val RECOGNIZER_MODEL = "receipt-ai|model"
        const val RECOGNIZER_KEY = "receipt-ai|apiKey"
    }
}
