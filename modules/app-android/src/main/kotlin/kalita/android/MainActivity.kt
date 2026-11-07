// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kalita.core.Dataset
import kalita.core.JournalColumn
import kalita.core.JournalRow
import kalita.core.SortKey
import kalita.presentation.Formatter
import kalita.presentation.JournalRowFormatter
import kalita.presentation.JournalState
import kalita.presentation.Strings
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
                    var addingOperation by remember(state) { mutableStateOf(false) }
                    LaunchedEffect(state) {
                        controller.journal.setSort(listOf(SortKey(JournalColumn.Date, descending = true)))
                    }
                    LaunchedEffect(chips) {
                        controller.journal.applyFilter(chips.toFilter())
                    }
                    if (addingOperation) {
                        val formState by controller.operationForm.state.collectAsState()
                        AddOperationScreen(
                            dataset = state.dataset,
                            formState = formState,
                            onFieldChange = controller.operationForm::updateField,
                            onSaveAndContinue = {
                                // FR-A3's "Сохранить и ещё" — a series: stays open, keeps the same
                                // kind so repeated entries of the same sort don't need re-picking it,
                                // exactly like the desktop OperationFormDialog's "Сохранить" button.
                                val kind = formState.draft.typeId?.let { state.dataset.operationType(it)?.kind }
                                (controller.addOperation(formState.draft) as? SubmitResult.Applied)?.also {
                                    controller.operationForm.newOperation(kind)
                                } != null
                            },
                            onSaveAndClose = {
                                (controller.addOperation(formState.draft) as? SubmitResult.Applied)?.also {
                                    addingOperation = false
                                } != null
                            },
                            onCancel = { addingOperation = false },
                        )
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
                                addingOperation = true
                            },
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kalita", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // FR-A3's quick entry — disabled read-only, same as every other write path
                // (AndroidAppController.submitChange already refuses it, this just avoids the
                // round trip through a form the user couldn't save anyway).
                if (!readOnly) {
                    Button(onClick = onAddOperation) { Text(Strings.ADD) }
                }
                Button(onClick = onExport) { Text("Экспорт") }
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
                    JournalRowItem(dataset, row)
                    HorizontalDivider()
                }
            }
        }
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

@Composable
private fun JournalRowItem(dataset: Dataset, row: JournalRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
}
