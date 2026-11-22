// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kalita.core.Dataset
import kalita.plugin.api.BankTransaction
import kalita.plugin.apibank.ApiBankStatementPlugin
import kalita.plugin.reconcile.BankMappingStore
import kalita.plugin.reconcile.CategoryRule
import kalita.plugin.reconcile.Decision
import kalita.plugin.reconcile.LinkKind
import kalita.plugin.reconcile.Proposal
import kalita.plugin.reconcile.ReconciliationReport
import kalita.plugin.reconcile.ReconcileConfig
import kalita.plugin.statement.CsvColumns
import kalita.plugin.statement.CsvPreview
import kalita.plugin.statement.StatementFilePlugin
import kalita.storage.api.SubmitResult
import kotlinx.coroutines.launch

internal fun saveBankMappings(
    store: BankMappingStore,
    original: BankMappingStore.Mappings,
    accounts: Map<String, Long?>,
    currencies: Map<String, Long?>,
) {
    accounts.forEach { (key, value) -> if (original.accounts[key] != value) store.setAccount(key, value) }
    currencies.forEach { (key, value) -> if (original.currencies[key] != value) store.setCurrency(key, value) }
}

internal fun rateTolerancePercent(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    ?.takeIf { it.isFinite() && it in 0.0..100.0 }?.div(100)

@Composable
internal fun BankMappingsScreen(dataset: Dataset, controller: AndroidAppController, onBack: () -> Unit) {
    val store = controller.bankMappings() ?: return
    val loaded = remember { runCatching { store.read() } }
    val original = loaded.getOrNull()
    var accounts by remember { mutableStateOf(original?.accounts?.mapValues { it.value as Long? }.orEmpty()) }
    var currencies by remember { mutableStateOf(original?.currencies?.mapValues { it.value as Long? }.orEmpty()) }
    var error by remember { mutableStateOf(loaded.exceptionOrNull()?.let { it.message ?: it.toString() }) }
    val availableAccounts = dataset.accounts.filter { !it.deleted }
    val availableCurrencies = dataset.currencies.filter { !it.deleted }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Соответствие счетов и валют")
        if (original != null) {
            if (accounts.isEmpty() && currencies.isEmpty()) Text("Сохранённых сопоставлений нет")
            if (accounts.isNotEmpty()) Text("Счета банка")
            accounts.forEach { (key, id) ->
                val selected = availableAccounts.firstOrNull { it.id == id }
                PickerField(key, selected?.name ?: if (id == null) "—" else "Недоступен: id:$id", availableAccounts, { it.name ?: "id:${it.id}" }, { accounts = accounts + (key to it.id) }, true, { accounts = accounts + (key to null) })
            }
            if (currencies.isNotEmpty()) Text("Валюты выписки")
            currencies.forEach { (key, id) ->
                val selected = availableCurrencies.firstOrNull { it.id == id }
                PickerField(key, selected?.name ?: if (id == null) "—" else "Недоступна: id:$id", availableCurrencies, { it.name ?: "id:${it.id}" }, { currencies = currencies + (key to it.id) }, true, { currencies = currencies + (key to null) })
            }
        }
        error?.let { Text(it) }
        Row {
            TextButton(onClick = onBack) { Text("Отмена") }
            if (original != null) Button(onClick = {
                runCatching { saveBankMappings(store, original, accounts, currencies) }
                    .onSuccess { onBack() }.onFailure { error = it.message ?: it.toString() }
            }) { Text("Сохранить") }
        }
    }
}

@Composable
internal fun CategoryRulesScreen(dataset: Dataset, controller: AndroidAppController, onBack: () -> Unit) {
    val store = controller.categoryRules() ?: return
    var rules by remember { mutableStateOf(store.read()) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Правила категоризации — первое совпадение имеет приоритет")
        rules.forEachIndexed { index, rule ->
            fun update(value: CategoryRule) { rules = rules.toMutableList().also { it[index] = value } }
            HorizontalDivider()
            Text("Правило ${index + 1}")
            PickerField("Поле", rule.field.name, CategoryRule.Field.entries, { it.name }, { update(rule.copy(field = it)) })
            PickerField("Совпадение", rule.match.name, CategoryRule.Match.entries, { it.name }, { update(rule.copy(match = it)) })
            OutlinedTextField(rule.pattern, { update(rule.copy(pattern = it)) }, label = { Text("Образец") }, modifier = Modifier.fillMaxWidth())
            PickerField("Статья", dataset.budgetItem(rule.budgetItemId)?.name ?: "—", dataset.budgetItems.filter { !it.deleted }, { it.name ?: "id:${it.id}" }, { update(rule.copy(budgetItemId = it.id)) }, true, { update(rule.copy(budgetItemId = null)) })
            PickerField("Кто/кому", dataset.counterparty(rule.counterpartyId)?.name ?: "—", dataset.counterparties.filter { !it.deleted }, { it.name ?: "id:${it.id}" }, { update(rule.copy(counterpartyId = it.id)) }, true, { update(rule.copy(counterpartyId = null)) })
            TextButton(onClick = { update(rule.copy(budgetItemId = null, counterpartyId = null)) }) { Text("Очистить назначения") }
            Row {
                TextButton(onClick = { if (index > 0) rules = rules.toMutableList().also { it[index] = it[index - 1].also { _ -> it[index - 1] = rule } } }) { Text("↑") }
                TextButton(onClick = { if (index < rules.lastIndex) rules = rules.toMutableList().also { it[index] = it[index + 1].also { _ -> it[index + 1] = rule } } }) { Text("↓") }
                TextButton(onClick = { rules = rules.filterIndexed { i, _ -> i != index } }) { Text("Удалить") }
            }
        }
        TextButton(onClick = { rules = rules + CategoryRule(CategoryRule.Field.MERCHANT, CategoryRule.Match.CONTAINS, "", null, null) }) { Text("Добавить") }
        error?.let { Text(it) }
        Row {
            TextButton(onClick = onBack) { Text("Отмена") }
            Button(onClick = {
                val bad = rules.withIndex().filter { !it.value.usable }.map { it.index + 1 }
                if (bad.isNotEmpty()) error = "Заполните правила: ${bad.joinToString()}" else {
                    runCatching { store.write(rules); onBack() }.onFailure { error = it.message }
                }
            }) { Text("Сохранить") }
        }
    }
}

@Composable
internal fun StatementImportScreen(dataset: Dataset, controller: AndroidAppController, pendingOnly: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { StatementFilePlugin() }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var name by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<CsvPreview?>(null) }
    var transactions by remember { mutableStateOf<List<BankTransaction>?>(null) }
    var report by remember { mutableStateOf<ReconciliationReport?>(null) }
    var groupIndex by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var matchForeign by remember { mutableStateOf(true) }
    var tolerance by remember { mutableStateOf("1") }
    androidx.compose.runtime.LaunchedEffect(pendingOnly) {
        if (pendingOnly) {
            runCatching { controller.pendingStatements() }
                .onSuccess { transactions = it; error = null }
                .onFailure { error = it.message ?: it.toString() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val pickedName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "statement.csv"
                    val data = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                    name = pickedName
                    bytes = data
                    transactions = null
                    report = null
                    groupIndex = 0
                    preview = if (pickedName.endsWith(".csv", true)) importer.previewCsv(data) else null
                    if (preview == null) transactions = controller.queueStatement(if (pickedName.endsWith(".xls", true)) ApiBankStatementPlugin().import(data, pickedName) else importer.import(data, pickedName))
                    error = null
                }.onFailure { error = it.message ?: it.toString() }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Импорт выписки")
        TextButton(onClick = onBack) { Text("Назад") }
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Выбрать CSV / OFX / QFX / XLS") }
        Button(onClick = {
            runCatching { controller.pendingStatements() }
                .onSuccess { transactions = it; preview = null; report = null; groupIndex = 0; error = null }
                .onFailure { error = it.message ?: it.toString() }
        }) { Text("Продолжить ожидающие выписки") }
        error?.let { Text(it) }
        val csv = preview
        if (csv != null && bytes != null) {
            CsvMappingScreen(csv) { start, columns ->
                runCatching { controller.queueStatement(importer.importCsv(bytes!!, name, start, columns)) }
                    .onSuccess { transactions = it; preview = null; error = null }
                    .onFailure { error = it.message ?: it.toString() }
            }
        }
        val txs = transactions
        if (txs != null) {
            val groups = txs.groupBy { it.bankAccount to it.currency.uppercase() }.entries.toList()
            if (groupIndex >= groups.size) {
                Text(if (groups.isEmpty()) "В выписке нет операций" else "Импорт завершён")
            } else {
                val group = groups[groupIndex]
                val mappings = controller.bankMappings()!!
                val loadedMappings = remember(groupIndex, txs) { runCatching { mappings.read() } }
                val savedMappings = loadedMappings.getOrNull()
                loadedMappings.exceptionOrNull()?.let { Text(it.message ?: it.toString()) }
                var account by remember(groupIndex, txs) { mutableStateOf(savedMappings?.accounts?.get(group.key.first)?.takeIf { id -> dataset.accounts.any { it.id == id && !it.deleted } }) }
                var currency by remember(groupIndex, txs) { mutableStateOf(savedMappings?.currencies?.get(group.key.second)?.takeIf { id -> dataset.currencies.any { it.id == id && !it.deleted } } ?: dataset.currencies.firstOrNull { it.name.equals(group.key.second, true) && !it.deleted }?.id) }
                Text("${group.key.first} · ${group.key.second}")
                if (report == null) {
                    PickerField("Счёт", dataset.account(account)?.name ?: "—", dataset.accounts.filter { !it.deleted }, { it.name ?: "id:${it.id}" }, { account = it.id })
                    PickerField("Валюта", dataset.currency(currency)?.name ?: "—", dataset.currencies.filter { !it.deleted }, { it.name ?: "id:${it.id}" }, { currency = it.id })
                    Row {
                        Checkbox(checked = matchForeign, onCheckedChange = { matchForeign = it })
                        Text("Сопоставлять операции в другой валюте")
                    }
                    val rateTolerance = if (matchForeign) rateTolerancePercent(tolerance) else -1.0
                    if (matchForeign) {
                        OutlinedTextField(tolerance, { tolerance = it }, label = { Text("Допуск по курсу, % (0–100)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = rateTolerance == null, singleLine = true)
                    }
                    Button(enabled = savedMappings != null && account != null && currency != null && rateTolerance != null, onClick = {
                        scope.launch {
                            runCatching {
                                mappings.setAccount(group.key.first, account)
                                mappings.setCurrency(group.key.second, currency)
                                report = controller.reconcile(group.value, account!!, currency!!, ReconcileConfig(rateTolerance = rateTolerance!!))
                                error = null
                            }.onFailure { error = it.message ?: it.toString() }
                        }
                    }) { Text("Сверить") }
                } else {
                    ReconciliationScreen(dataset, report!!, onApply = { decisions, rules ->
                        runCatching { controller.applyImport(decisions, account!!) }.onSuccess { result -> when (result) {
                            is SubmitResult.Applied -> {
                                report = null; groupIndex++
                                error = runCatching { rules.asReversed().forEach { controller.categoryRules()?.add(it) } }
                                    .exceptionOrNull()?.let { it.message ?: it.toString() }
                            }
                            is SubmitResult.Failed -> error = result.reason
                            is SubmitResult.Conflicts -> error = "Данные изменились, повторите импорт"
                        } }.onFailure { report = null; error = it.message ?: it.toString() }
                    }, onSkip = { report = null; groupIndex++ })
                }
            }
        }
    }
}

@Composable
private fun CsvMappingScreen(preview: CsvPreview, onImport: (Int, CsvColumns) -> Unit) {
    val suggested = preview.suggestedColumns
    var fields by remember(preview) { mutableStateOf(listOf(suggested?.date, suggested?.amount, suggested?.debit, suggested?.credit, suggested?.currency, suggested?.description, suggested?.account, suggested?.id, suggested?.status)) }
    var start by remember(preview) { mutableStateOf(((preview.headerRow ?: -1) + 2).toString()) }
    val width = preview.rows.maxOfOrNull { it.size } ?: 0
    Text("Предпросмотр (пустые строки исключены)")
    preview.rows.take(5).forEachIndexed { i, row -> Text("${i + 1}: ${row.joinToString(" | ")}") }
    OutlinedTextField(start, { start = it }, label = { Text("Первая строка данных (с 1)") })
    listOf("Дата", "Сумма", "Расход", "Приход", "Валюта", "Описание", "Счёт", "ID", "Статус").forEachIndexed { i, label ->
        PickerField(label, fields[i]?.let { "${it + 1}: ${preview.rows.getOrNull(preview.headerRow ?: 0)?.getOrNull(it).orEmpty()}" } ?: "—", (0 until width).toList(), { "${it + 1}: ${preview.rows.getOrNull(preview.headerRow ?: 0)?.getOrNull(it).orEmpty()}" }, { column -> fields = fields.toMutableList().also { it[i] = column } }, true, { fields = fields.toMutableList().also { it[i] = null } })
        if (fields[i] != null) TextButton(onClick = { fields = fields.toMutableList().also { it[i] = null } }) { Text("Очистить: $label") }
    }
    val first = start.toIntOrNull()?.minus(1)
    val selected = fields.filterNotNull()
    val valid = first != null && first in 0 until preview.rows.size && fields[0] != null && fields.slice(1..3).any { it != null } && selected.distinct().size == selected.size
    Button(enabled = valid, onClick = { onImport(first!!, CsvColumns(fields[0]!!, fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8])) }) { Text("Прочитать") }
}

@Composable
private fun ReconciliationScreen(dataset: Dataset, report: ReconciliationReport, onApply: (List<Decision>, List<CategoryRule>) -> Unit, onSkip: () -> Unit) {
    var decisions by remember(report) { mutableStateOf<Map<Int, Decision>>(emptyMap()) }
    var rememberRules by remember(report) { mutableStateOf<Set<Int>>(emptySet()) }
    Text("Уже связаны: ${report.skippedAlreadyLinked}; ожидают: ${report.skippedPending}")
    report.proposals.forEachIndexed { i, proposal ->
        HorizontalDivider()
        Text("${proposal.transaction.postedAt} · ${proposal.transaction.amount} · ${proposal.transaction.description}")
        val choices = mutableListOf<Pair<String, Decision?>>("Пропустить" to null, "Игнорировать навсегда" to Decision.Ignore(proposal))
        when (proposal) {
            is Proposal.Exact -> {
                choices += "Связать #${proposal.operation.id}" to Decision.Link(proposal, listOf(proposal.operation), LinkKind.EXACT)
                proposal.alternatives.forEach { choices += "Связать #${it.id}" to Decision.Link(proposal, listOf(it), LinkKind.MANUAL) }
            }
            is Proposal.Sum -> choices += "Связать группу ${proposal.operations.joinToString { "#${it.id}" }}" to Decision.Link(proposal, proposal.operations, LinkKind.SUM)
            is Proposal.New -> {
                choices += "Создать" to Decision.Create(proposal, proposal.draft)
                proposal.similarElsewhere.forEach { choices += "Перенести #${it.id}" to Decision.MoveToAccount(proposal, it) }
            }
        }
        val decision = decisions[i]
        PickerField("Действие", choices.firstOrNull { it.second == decision }?.first ?: if (decision is Decision.Create) "Создать" else "Пропустить", choices, { it.first }, { choice -> decisions = if (choice.second == null) decisions - i else decisions + (i to choice.second!!) })
        if (decision is Decision.Create) {
            PickerField("Статья", dataset.budgetItem(decision.draft.budgetItemId)?.name ?: "—", dataset.budgetItems.filter { !it.deleted }, { it.name ?: "id:${it.id}" }, { decisions = decisions + (i to decision.copy(draft = decision.draft.copy(budgetItemId = it.id))) })
            PickerField("Кто/кому", dataset.counterparty(decision.draft.counterpartyId)?.name ?: "—", dataset.counterparties.filter { !it.deleted }, { it.name ?: "id:${it.id}" }, { decisions = decisions + (i to decision.copy(draft = decision.draft.copy(counterpartyId = it.id))) }, true, { decisions = decisions + (i to decision.copy(draft = decision.draft.copy(counterpartyId = null))) })
            TextButton(onClick = { decisions = decisions + (i to decision.copy(draft = decision.draft.copy(counterpartyId = null))) }) { Text("Очистить кто/кому") }
            Row { Checkbox(i in rememberRules, { rememberRules = if (it) rememberRules + i else rememberRules - i }); Text("Запомнить правило") }
        }
    }
    Text("Нет в выписке:")
    report.notInBank.forEach { Text("#${it.id} · ${it.date} · ${it.amount} · ${it.comment.orEmpty()}") }
    Row {
        TextButton(onClick = onSkip) { Text("Пропустить группу") }
        Button(onClick = {
            val rules = decisions.filterKeys { it in rememberRules }.values.filterIsInstance<Decision.Create>().map {
                CategoryRule.fromBankLine(it.proposal.transaction.description, it.proposal.transaction.merchant, it.draft.budgetItemId, it.draft.counterpartyId)
            }.filter { it.usable }
            onApply(decisions.toSortedMap().values.toList(), rules)
        }) { Text("Применить") }
    }
}