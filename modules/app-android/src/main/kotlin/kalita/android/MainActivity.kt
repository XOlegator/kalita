// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kalita.core.Dataset
import kalita.core.JournalColumn
import kalita.core.JournalRow
import kalita.core.Operation
import kalita.core.OperationDraft
import kalita.core.SaldoLine
import kalita.core.SortKey
import kalita.presentation.Formatter
import kalita.presentation.JournalRowFormatter
import kalita.presentation.JournalState
import kalita.presentation.SaldoState
import kalita.presentation.Strings
import java.math.BigDecimal
import kalita.storage.api.SubmitResult

/**
 * dev_plan.md Этап 10, FR-A1 (full) + FR-A2 (full, this срез — the day grouping/chips/search/show-
 * deleted that срез 1–2 left as a "minimal" plain list): pick a `.mdb` via the system file picker,
 * open it through [AndroidAppController], browse its journal grouped by day with daily totals,
 * filter by period/type/account/budget item/counterparty chips and free text, export a copy back
 * out. Sorting is fixed to date-descending (newest day on top) rather than the desktop grid's
 * interactive multi-sort — a deliberate scope cut for the phone screen, not an oversight.
 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: AndroidAppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = AndroidAppController(filesDir)
        // FR-A1: "the last database opens at launch" — reopens Data.mdb if a previous run left
        // one in app storage, instead of always starting at the picker.
        controller.openIfExists()
        setContent {
            KalitaApp(controller)
        }
    }
}

/** Which form the Compose tree should show in place of the journal, and how saving it behaves
 * (FR-A3/A4): `editId == null` is a fresh insert — a plain "Добавить", or "Копировать" pre-filled
 * from an existing row — while non-null overwrites that same row instead. */
private data class OperationFormMode(val editId: Long? = null, val title: String = Strings.NEW_OPERATION)

private fun draftFromOperation(op: Operation): OperationDraft = OperationDraft(
    date = op.date, amount = op.amount, currencyId = op.currencyId,
    budgetItemId = op.budgetItemId, typeId = op.typeId, comment = op.comment,
    counterpartyId = op.counterpartyId, accountFrom = op.accountFrom, accountTo = op.accountTo,
)

@Composable
private fun KalitaApp(controller: AndroidAppController) {
    val appState by controller.state.collectAsState()
    val journalState by controller.journal.state.collectAsState()
    val context = LocalContext.current

    val pickDatabase = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { picked ->
            context.contentResolver.openInputStream(picked)?.use { controller.importAndOpen(it) }
        }
    }
    val openPicker = { pickDatabase.launch(arrayOf("*/*")) }

    // FR-A1's other half: export the local copy back out through SAF. A plain byte copy — see
    // AndroidAppController.exportTo's kdoc for why that's safe while the session stays open.
    val exportDatabase = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-msaccess")) { uri ->
        uri?.let { target ->
            context.contentResolver.openOutputStream(target)?.use { controller.exportTo(it) }
        }
    }
    val exportPicker = { exportDatabase.launch("Data.mdb") }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val state = appState) {
                is AndroidAppState.NoDatabase -> OpenPrompt(onOpen = openPicker)
                is AndroidAppState.Error -> OpenPrompt(onOpen = openPicker, error = state.message)
                is AndroidAppState.Ready -> {
                    // Keyed on `state` (a fresh Ready instance only at open/import time, never on
                    // a mere filter recompute — see AndroidAppController.openPrivateFile), so a
                    // newly opened database starts from the default chips, not the previous one's.
                    var chips by remember(state) { mutableStateOf(JournalChipSelection()) }
                    var formMode by remember(state) { mutableStateOf<OperationFormMode?>(null) }
                    var showingSaldo by remember(state) { mutableStateOf(false) }
                    LaunchedEffect(state) {
                        controller.journal.setSort(listOf(SortKey(JournalColumn.Date, descending = true)))
                    }
                    LaunchedEffect(chips) {
                        controller.journal.applyFilter(chips.toFilter())
                    }
                    val mode = formMode
                    if (mode != null) {
                        val formState by controller.operationForm.state.collectAsState()
                        AddOperationScreen(
                            title = mode.title,
                            dataset = state.dataset,
                            formState = formState,
                            onFieldChange = controller.operationForm::updateField,
                            onSaveAndContinue = if (mode.editId != null) {
                                null // FR-A4: editing a specific row has no "and continue" series.
                            } else {
                                {
                                    // FR-A3's "Сохранить и ещё" — a series: stays open, keeps the
                                    // same kind so repeated entries of the same sort don't need
                                    // re-picking it, exactly like the desktop dialog's "Сохранить".
                                    val kind = formState.draft.typeId?.let { state.dataset.operationType(it)?.kind }
                                    (controller.addOperation(formState.draft) as? SubmitResult.Applied)?.also {
                                        controller.operationForm.newOperation(kind)
                                    } != null
                                }
                            },
                            onSaveAndClose = {
                                val result = if (mode.editId != null) {
                                    controller.updateOperation(mode.editId, formState.draft)
                                } else {
                                    controller.addOperation(formState.draft)
                                }
                                (result as? SubmitResult.Applied)?.also { formMode = null } != null
                            },
                            onCancel = { formMode = null },
                        )
                    } else if (showingSaldo) {
                        val saldoState by controller.saldo.state.collectAsState()
                        SaldoScreen(dataset = state.dataset, saldoState = saldoState, onBack = { showingSaldo = false })
                    } else {
                        JournalScreen(
                            dataset = state.dataset,
                            journalState = journalState,
                            chips = chips,
                            onChipsChange = { chips = it },
                            onSearchChange = controller.journal::setTextFilter,
                            onShowDeletedChange = controller.journal::setShowDeleted,
                            readOnly = state.readOnly,
                            onExport = exportPicker,
                            onAddOperation = {
                                controller.operationForm.newOperation()
                                formMode = OperationFormMode()
                            },
                            onEditOperation = { row ->
                                controller.operationForm.editOperation(draftFromOperation(row.operation))
                                formMode = OperationFormMode(editId = row.operation.id, title = Strings.EDIT_OPERATION)
                            },
                            onCopyOperation = { row ->
                                controller.operationForm.editOperation(draftFromOperation(row.operation))
                                formMode = OperationFormMode(title = Strings.COPY_OPERATION)
                            },
                            onDeleteOperation = { id -> controller.softDeleteOperations(listOf(id)) },
                            onRestoreOperation = { id -> controller.restoreOperations(listOf(id)) },
                            onOpenSaldo = { showingSaldo = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenPrompt(onOpen: () -> Unit, error: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kalita", style = MaterialTheme.typography.headlineLarge)
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onOpen) { Text("Открыть базу (Data.mdb)") }
    }
}

@Composable
private fun JournalScreen(
    dataset: Dataset,
    journalState: JournalState,
    chips: JournalChipSelection,
    onChipsChange: (JournalChipSelection) -> Unit,
    onSearchChange: (String) -> Unit,
    onShowDeletedChange: (Boolean) -> Unit,
    readOnly: Boolean,
    onExport: () -> Unit,
    onAddOperation: () -> Unit,
    onEditOperation: (JournalRow) -> Unit,
    onCopyOperation: (JournalRow) -> Unit,
    onDeleteOperation: (Long) -> Unit,
    onRestoreOperation: (Long) -> Unit,
    onOpenSaldo: () -> Unit,
) {
    // Confirms before a destructive soft-delete (FR-A4), same as the desktop grid's own
    // QMessageBox — restore has no equivalent confirmation there either, so none here.
    var confirmDelete by remember { mutableStateOf<JournalRow?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kalita", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // FR-A5: read-only, so unlike Добавить/Экспорт it stays available read-only too.
                TextButton(onClick = onOpenSaldo) { Text(Strings.SALDO_TITLE) }
                TextButton(onClick = onExport) { Text("Экспорт") }
                // FR-A3's quick entry — disabled read-only, same as every other write path
                // (AndroidAppController.submitChange already refuses it, this just avoids the
                // round trip through a form the user couldn't save anyway).
                if (!readOnly) {
                    Button(onClick = onAddOperation) { Text(Strings.ADD) }
                }
            }
        }
        OutlinedTextField(
            value = journalState.textFilter,
            onValueChange = onSearchChange,
            placeholder = { Text(Strings.SEARCH) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        FilterChipsRow(dataset = dataset, chips = chips, onChipsChange = onChipsChange)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = journalState.showDeleted,
                onClick = { onShowDeletedChange(!journalState.showDeleted) },
                label = { Text(Strings.SHOW_DELETED) },
            )
        }
        HorizontalDivider()
        if (readOnly) {
            Text(
                "Только просмотр — база открыта в другом месте",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider()
        }
        val groups = remember(journalState.rows) { groupByDay(journalState.rows) }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            groups.forEach { group ->
                item(key = "header-${group.date}") { DayHeader(group) }
                items(group.rows, key = { it.operation.id }) { row ->
                    JournalRowItem(
                        dataset = dataset,
                        row = row,
                        readOnly = readOnly,
                        onEdit = { onEditOperation(row) },
                        onCopy = { onCopyOperation(row) },
                        onDelete = { confirmDelete = row },
                        onRestore = { onRestoreOperation(row.operation.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(Strings.CONFIRM_DELETE_TITLE) },
            text = { Text(Strings.confirmDeleteOperations(1)) },
            confirmButton = {
                TextButton(onClick = { onDeleteOperation(row.operation.id); confirmDelete = null }) {
                    Text(Strings.DELETE)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(Strings.CANCEL) } },
        )
    }
}

@Composable
private fun FilterChipsRow(dataset: Dataset, chips: JournalChipSelection, onChipsChange: (JournalChipSelection) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (period in PeriodChip.entries) {
            FilterChip(
                selected = chips.period == period,
                onClick = { onChipsChange(chips.copy(period = period)) },
                label = { Text(periodLabel(period)) },
            )
        }
        MultiSelectFilterChip(
            label = Strings.OPERATION_TYPE,
            items = dataset.operationTypes.filter { !it.deleted },
            idOf = { it.id },
            nameOf = { it.name ?: "—" },
            selectedIds = chips.typeIds,
            onChange = { onChipsChange(chips.copy(typeIds = it)) },
        )
        MultiSelectFilterChip(
            label = Strings.ACCOUNT,
            items = dataset.accounts.filter { !it.deleted },
            idOf = { it.id },
            nameOf = { it.name ?: "—" },
            selectedIds = chips.accountIds,
            onChange = { onChipsChange(chips.copy(accountIds = it)) },
        )
        MultiSelectFilterChip(
            label = Strings.BUDGET_ITEM,
            items = dataset.budgetItems.filter { !it.deleted },
            idOf = { it.id },
            nameOf = { it.name ?: "—" },
            selectedIds = chips.budgetItemIds,
            onChange = { onChipsChange(chips.copy(budgetItemIds = it)) },
        )
        MultiSelectFilterChip(
            label = Strings.COUNTERPARTY,
            items = dataset.counterparties.filter { !it.deleted },
            idOf = { it.id },
            nameOf = { it.name ?: "—" },
            selectedIds = chips.counterpartyIds,
            onChange = { onChipsChange(chips.copy(counterpartyIds = it)) },
        )
    }
}

private fun periodLabel(period: PeriodChip): String = when (period) {
    PeriodChip.Today -> Strings.DATE_MODE_TODAY
    PeriodChip.Week -> Strings.DATE_MODE_WEEK
    PeriodChip.Month -> Strings.DATE_MODE_MONTH
    PeriodChip.Quarter -> Strings.DATE_MODE_QUARTER
    PeriodChip.Year -> Strings.DATE_MODE_YEAR
    PeriodChip.All -> Strings.DATE_MODE_ALL
}

/** A chip that opens a checklist dropdown — the mobile stand-in for the desktop panel's checked
 * reference list (dev_plan.md §6.3), scaled down to one tap-target regardless of how many items
 * [items] holds. */
@Composable
private fun <T> MultiSelectFilterChip(
    label: String,
    items: List<T>,
    idOf: (T) -> Long,
    nameOf: (T) -> String,
    selectedIds: Set<Long>,
    onChange: (Set<Long>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selectedIds.isNotEmpty(),
            onClick = { expanded = true },
            label = { Text(if (selectedIds.isEmpty()) label else "$label (${selectedIds.size})") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                val id = idOf(item)
                DropdownMenuItem(
                    text = { Text(nameOf(item)) },
                    leadingIcon = { Checkbox(checked = id in selectedIds, onCheckedChange = null) },
                    onClick = { onChange(if (id in selectedIds) selectedIds - id else selectedIds + id) },
                )
            }
        }
    }
}

@Composable
private fun DayHeader(group: JournalDayGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            group.date?.let(Formatter::date) ?: "Без даты",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            group.total?.let(Formatter::amount) ?: "—",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** FR-A4: tapping a (non-deleted, writable) row edits it directly — the overflow menu covers the
 * actions a tap can't express: copy, soft-delete, or (for an already-deleted row, shown only when
 * "Показать удалённые" is on) restore. */
@Composable
private fun JournalRowItem(
    dataset: Dataset,
    row: JournalRow,
    readOnly: Boolean,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    val deleted = row.operation.deleted
    val rowModifier = Modifier.fillMaxWidth().let {
        if (!readOnly && !deleted) it.clickable(onClick = onEdit) else it
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                JournalRowFormatter.cellText(dataset, row, JournalColumn.BudgetItem),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                JournalRowFormatter.cellText(dataset, row, JournalColumn.Amount),
                style = MaterialTheme.typography.titleMedium,
            )
            val comment = JournalRowFormatter.cellText(dataset, row, JournalColumn.Comment)
            if (comment.isNotEmpty()) {
                Text(comment, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!readOnly) {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) { Text("⋮") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (deleted) {
                        DropdownMenuItem(
                            text = { Text(Strings.RESTORE) },
                            onClick = { menuExpanded = false; onRestore() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(Strings.COPY) },
                            onClick = { menuExpanded = false; onCopy() },
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.DELETE) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/** FR-A5 (dev_plan.md Этап 10): per-account balances with a «ИТОГО» footer in the reduction
 * currency — [kalita.presentation.SaldoPresenter] already computes everything via the shared
 * [kalita.core.SaldoService], the same one the desktop `SaldoPanel` reads; this just lists it
 * instead of a table, line per (account, currency), FR-A8's "phone — screens" layout cut. */
@Composable
private fun SaldoScreen(dataset: Dataset, saldoState: SaldoState, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Strings.SALDO_TITLE, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text(Strings.CANCEL) }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(saldoState.saldo.lines) { line -> SaldoLineRow(dataset, line) }
            item { HorizontalDivider() }
            item { SaldoTotalRow(saldoState) }
        }
    }
}

@Composable
private fun SaldoLineRow(dataset: Dataset, line: SaldoLine) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(dataset.account(line.accountId)?.name ?: "id:${line.accountId}", style = MaterialTheme.typography.bodyLarge)
            val currency = line.currencyId?.let { dataset.currency(it)?.name }
            Text(
                "${Strings.RECEIVED}: ${Formatter.amount(line.received)}  ${Strings.SPENT}: ${Formatter.amount(line.spent)}" +
                    (currency?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            Formatter.amount(line.balance),
            style = MaterialTheme.typography.titleMedium,
            color = if (line.balance < BigDecimal.ZERO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SaldoTotalRow(saldoState: SaldoState) {
    val total = saldoState.saldo.total
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(Strings.TOTAL, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                "${Strings.RECEIVED}: ${Formatter.amount(total.received)}  ${Strings.SPENT}: ${Formatter.amount(total.spent)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            Formatter.amount(total.balance),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (total.balance < BigDecimal.ZERO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
