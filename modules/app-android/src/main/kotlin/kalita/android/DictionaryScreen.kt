// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kalita.core.CurrencyCatalog
import kalita.plugin.host.RateLookup
import kalita.presentation.DictionaryState
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import kotlinx.coroutines.launch

/** Which flat (or tree-shaped, for [BudgetItems]) dictionary this screen shows — the Android
 * equivalent of the desktop `DictionaryKind` (kalita.app.desktop.DictionaryBrowser), just one
 * screen with tabs instead of one widget per kind, since a phone has no room for several docked
 * panels at once. */
enum class DictionaryKind(val title: String) {
    Accounts(Strings.ACCOUNTS_TITLE),
    Counterparties(Strings.COUNTERPARTIES_TITLE),
    Currencies(Strings.CURRENCIES_TITLE),
    BudgetItems(Strings.BUDGET_ITEMS_TITLE),
}

/** One row of any of the four dictionaries, flattened to a common shape; [depth] is always 0
 * except for [DictionaryKind.BudgetItems], where it is the item's distance from a tree root
 * ([kalita.core.BudgetTree.path]) — the only hint of hierarchy FR-A6 keeps, since reordering
 * ("перестановки") is explicitly left to the desktop app. */
private data class DictRow(val id: Long, val name: String?, val deleted: Boolean, val depth: Int = 0)

private fun rowsFor(kind: DictionaryKind, state: DictionaryState): List<DictRow> = when (kind) {
    DictionaryKind.Accounts -> state.accounts.map { DictRow(it.id, it.name, it.deleted) }
    DictionaryKind.Counterparties -> state.counterparties.map { DictRow(it.id, it.name, it.deleted) }
    DictionaryKind.Currencies -> state.currencies.map { DictRow(it.id, it.name, it.deleted) }
    DictionaryKind.BudgetItems -> state.budgetTree.preorder().map { item ->
        DictRow(item.id, item.name, item.deleted, depth = state.budgetTree.path(item.id).size - 1)
    }
}

/** `id == null` means a fresh insert; [parentId] only matters for [DictionaryKind.BudgetItems]
 * ("Подуровень" on a row sets it to that row's id, the plain "Добавить" button leaves it `null` —
 * a new root, mirroring the desktop browser's own two actions). */
private data class DictFormState(
    val kind: DictionaryKind,
    val id: Long? = null,
    val parentId: Long? = null,
    val name: String = "",
    val cbId: String = "",
)

/**
 * FR-A6 (dev_plan.md Этап 10): view/add/edit/soft-delete the three flat dictionaries and the
 * budget item tree, mirroring the desktop `DictionaryBrowser`/`BudgetTreeBrowser`'s operations —
 * just one screen with a tab per kind instead of one widget each. FR-A8's "tablet: list and card
 * side by side" ([isWideScreen]): on a wide screen the edit form renders as an inline card next to
 * the list instead of a modal [AlertDialog], the same split [MainActivity] uses for the journal
 * and its operation form.
 */
@Composable
fun DictionaryScreen(
    state: DictionaryState,
    readOnly: Boolean,
    referenceCount: (DictionaryKind, Long) -> Int,
    onBack: () -> Unit,
    onSearchChange: (String) -> Unit,
    onShowDeletedChange: (Boolean) -> Unit,
    onAdd: (DictionaryKind, Long?, String, String?, Double?) -> SubmitResult,
    onEdit: (DictionaryKind, Long, String, String?) -> SubmitResult,
    onDelete: (DictionaryKind, Long) -> SubmitResult,
    onRestore: (DictionaryKind, Long) -> SubmitResult,
    /** «Из Интернета» in the new-currency form: the rate in force today, or why there is none. */
    onLookupRate: suspend (name: String, cbId: String?) -> RateLookup = { _, _ -> RateLookup.Unavailable(Strings.RATE_LOAD_NO_SOURCE) },
    /** «Курсы из Интернета» on the currencies tab: loads today's rates, returns a line to show. */
    onLoadRates: suspend () -> String = { Strings.RATE_LOAD_NO_SOURCE },
) {
    var kind by remember { mutableStateOf(DictionaryKind.Accounts) }
    var formState by remember { mutableStateOf<DictFormState?>(null) }
    var confirmDelete by remember { mutableStateOf<DictRow?>(null) }
    val wide = isWideScreen()

    fun closeForm() { formState = null }
    fun submitForm(name: String, cbId: String, rate: Double?) {
        val fs = formState ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        // An empty code is stored as what the name suggests (CurrencyCatalog), for currencies only.
        val code = if (fs.kind == DictionaryKind.Currencies) CurrencyCatalog.codeOrSuggestion(trimmedName, cbId) else null
        val result = if (fs.id != null) {
            onEdit(fs.kind, fs.id, trimmedName, code)
        } else {
            onAdd(fs.kind, fs.parentId, trimmedName, code, rate)
        }
        if (result is SubmitResult.Applied) closeForm()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Справочники", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text(Strings.CANCEL) }
        }
        SecondaryScrollableTabRow(selectedTabIndex = kind.ordinal) {
            for (entry in DictionaryKind.entries) {
                Tab(
                    selected = kind == entry,
                    onClick = { kind = entry; formState = null },
                    text = { Text(entry.title) },
                )
            }
        }
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(Strings.SEARCH) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        FilterChip(
            selected = state.showDeleted,
            onClick = { onShowDeletedChange(!state.showDeleted) },
            label = { Text(Strings.SHOW_DELETED) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (kind == DictionaryKind.Currencies && !readOnly) {
            val scope = rememberCoroutineScope()
            var loading by remember { mutableStateOf(false) }
            var loadMessage by remember { mutableStateOf<String?>(null) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        scope.launch {
                            loadMessage = onLoadRates()
                            loading = false
                        }
                    },
                ) { Text(if (loading) "Загрузка…" else Strings.RATES_FROM_INTERNET) }
            }
            loadMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        HorizontalDivider()

        val rows = rowsFor(kind, state)
        val list: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(rows, key = { it.id }) { row ->
                        DictRowItem(
                            row = row,
                            readOnly = readOnly,
                            allowSublevel = kind == DictionaryKind.BudgetItems,
                            onTap = {
                                if (!readOnly && !row.deleted) {
                                    val cbId = if (kind == DictionaryKind.Currencies) {
                                        state.currencies.firstOrNull { it.id == row.id }?.cbId ?: ""
                                    } else ""
                                    formState = DictFormState(kind, id = row.id, name = row.name ?: "", cbId = cbId)
                                }
                            },
                            onAddSublevel = { formState = DictFormState(kind, parentId = row.id) },
                            onDelete = { confirmDelete = row },
                            onRestore = { onRestore(kind, row.id) },
                        )
                        HorizontalDivider()
                    }
                }
                if (!readOnly) {
                    Button(
                        onClick = { formState = DictFormState(kind) },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text(Strings.ADD) }
                }
            }
        }

        if (wide && formState != null) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) { list() }
                VerticalDivider()
                Column(Modifier.weight(1f).padding(16.dp)) {
                    DictFormFields(formState!!, onSubmit = ::submitForm, onCancel = ::closeForm, onLookupRate = onLookupRate)
                }
            }
        } else {
            list()
        }
    }

    if (!wide) {
        formState?.let { fs ->
            // The dialog's own buttons are embedded inside `text` via DictFormFields rather than
            // Material3's confirmButton/dismissButton slots: those slots sit outside the field
            // composable's local text state, which would otherwise need its own hoisted state
            // just to let an external button read the current field values before submit.
            AlertDialog(
                onDismissRequest = ::closeForm,
                confirmButton = {},
                title = { Text(if (fs.id != null) Strings.EDIT else if (fs.parentId != null) Strings.ADD_SUBLEVEL else Strings.ADD) },
                text = { DictFormFields(fs, onSubmit = ::submitForm, onCancel = ::closeForm, onLookupRate = onLookupRate) },
            )
        }
    }

    confirmDelete?.let { row ->
        val refCount = referenceCount(kind, row.id)
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(Strings.CONFIRM_DELETE_TITLE) },
            text = {
                Text(
                    if (refCount > 0) Strings.confirmDeleteWithRefs(row.name ?: "?", refCount)
                    else Strings.confirmDelete(row.name ?: "?"),
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(kind, row.id); confirmDelete = null }) { Text(Strings.DELETE) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(Strings.CANCEL) } },
        )
    }
}

/**
 * The name fields and Save/Cancel buttons, shared by the modal dialog (phone) and the inline card
 * (tablet, FR-A8). A currency also gets its code — the placeholder shows what the name suggests,
 * and that is what an empty field is saved as — and, when it is being added, «Курс на сегодня»
 * with «Из Интернета»: a currency without a rate has no converted amounts, so saving without one
 * asks first.
 */
@Composable
private fun DictFormFields(
    initial: DictFormState,
    onSubmit: (name: String, cbId: String, rate: Double?) -> Unit,
    onCancel: () -> Unit,
    onLookupRate: suspend (name: String, cbId: String?) -> RateLookup,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var cbId by remember(initial) { mutableStateOf(initial.cbId) }
    var rate by remember(initial) { mutableStateOf("") }
    var rateNote by remember(initial) { mutableStateOf(Strings.CURRENCY_RATE_HINT) }
    var fetching by remember(initial) { mutableStateOf(false) }
    var confirmNoRate by remember(initial) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isCurrency = initial.kind == DictionaryKind.Currencies
    val adding = initial.id == null
    val parsedRate = parseRate(rate)
    val rateInvalid = rate.isNotBlank() && parsedRate == null
    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(Strings.NAME) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isCurrency) {
            OutlinedTextField(
                value = cbId,
                onValueChange = { cbId = it },
                label = { Text(Strings.CURRENCY_CODE) },
                placeholder = { CurrencyCatalog.codeFor(name)?.let { Text(Strings.currencyCodeSuggested(it)) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        if (isCurrency && adding) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it },
                    label = { Text(Strings.CURRENCY_RATE_TODAY) },
                    isError = rateInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !fetching && name.isNotBlank(),
                    onClick = {
                        fetching = true
                        scope.launch {
                            when (val found = onLookupRate(name.trim(), cbId.trim().ifEmpty { null })) {
                                is RateLookup.Found -> {
                                    rate = found.value.toPlainString()
                                    rateNote = Strings.currencyRateFound(found.sourceName, Formatter.date(found.date))
                                }
                                is RateLookup.Unavailable -> rateNote = found.reason
                            }
                            fetching = false
                        }
                    },
                ) { Text(if (fetching) "…" else Strings.CURRENCY_RATE_FROM_INTERNET) }
            }
            Text(
                if (rateInvalid) Strings.CURRENCY_RATE_INVALID else rateNote,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text(Strings.CANCEL) }
            Button(
                enabled = name.isNotBlank() && !rateInvalid,
                onClick = {
                    if (isCurrency && adding && parsedRate == null) confirmNoRate = true else onSubmit(name, cbId, parsedRate)
                },
            ) { Text(Strings.SAVE) }
        }
    }
    if (confirmNoRate) {
        AlertDialog(
            onDismissRequest = { confirmNoRate = false },
            text = { Text(Strings.CURRENCY_NO_RATE_QUESTION) },
            confirmButton = {
                TextButton(onClick = { confirmNoRate = false; onSubmit(name, cbId, null) }) { Text(Strings.ADD) }
            },
            dismissButton = { TextButton(onClick = { confirmNoRate = false }) { Text(Strings.CANCEL) } },
        )
    }
}

/** A typed rate: comma or point, spaces ignored; `null` unless it is a number greater than zero. */
internal fun parseRate(text: String): Double? = text.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()?.takeIf { it > 0.0 }

@Composable
private fun DictRowItem(
    row: DictRow,
    readOnly: Boolean,
    allowSublevel: Boolean,
    onTap: () -> Unit,
    onAddSublevel: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(start = (16 + row.depth * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.weight(1f).let {
                if (!readOnly && !row.deleted) it.clickable(onClick = onTap) else it
            },
        ) {
            Text(
                (row.name ?: "—") + if (row.deleted) " [удалён]" else "",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (!readOnly) {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) { Text("⋮") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (row.deleted) {
                        DropdownMenuItem(
                            text = { Text(Strings.RESTORE) },
                            onClick = { menuExpanded = false; onRestore() },
                        )
                    } else {
                        if (allowSublevel) {
                            DropdownMenuItem(
                                text = { Text(Strings.ADD_SUBLEVEL) },
                                onClick = { menuExpanded = false; onAddSublevel() },
                            )
                        }
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
