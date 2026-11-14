// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kalita.core.BudgetItem
import kalita.core.BudgetTree
import kalita.core.Dataset
import kalita.core.OperationDraft
import kalita.presentation.Formatter
import kalita.presentation.OperationFormState
import kalita.presentation.Strings
import java.time.LocalDate

/**
 * FR-A3 (dev_plan.md Этап 10): quick operation entry — amount, type, budget item (a short
 * "recently used" row plus the full flat list, same no-subtree-drill scope cut as FR-A2's chips;
 * no literal on-screen calculator, just a decimal keypad via [KeyboardType.Decimal] — out of scope
 * for this slice), default account (from [kalita.core.OperationRules.defaults], already applied by
 * [kalita.presentation.OperationFormPresenter.newOperation]), date, counterparty, comment.
 *
 * Mirrors the desktop `OperationFormDialog`'s two save buttons exactly: [onSaveAndContinue]
 * ("Сохранить", a series — the caller resets the form for another entry of the same kind, FR-A3's
 * "Сохранить и ещё") and [onSaveAndClose] ("Сохранить и выйти"). [onSaveAndContinue] is `null`
 * when editing an existing operation (FR-A4) — a series makes sense for a fresh entry (add, or add
 * pre-filled from "Копировать"), not for "keep editing the same row again right after saving it".
 */
@Composable
fun AddOperationScreen(
    title: String,
    dataset: Dataset,
    formState: OperationFormState,
    onFieldChange: (OperationDraft.() -> OperationDraft) -> Unit,
    onSaveAndContinue: (() -> Boolean)?,
    onSaveAndClose: () -> Boolean,
    onCancel: () -> Unit,
) {
    val draft = formState.draft
    var saveError by remember { mutableStateOf<String?>(null) }
    var calculating by remember { mutableStateOf(false) }

    fun trySave(action: () -> Boolean) {
        saveError = if (formState.errors.isNotEmpty()) {
            formState.errors.joinToString("\n") { Strings.validationError(it) }
        } else if (!action()) {
            "Не удалось сохранить"
        } else {
            null
        }
    }

    if (calculating) {
        CalculatorDialog(
            initial = draft.amount,
            onResult = { value -> onFieldChange { copy(amount = value) }; calculating = false },
            onDismiss = { calculating = false },
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onCancel) { Text(Strings.CANCEL) }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.amount?.toPlainString() ?: "",
                onValueChange = { text ->
                    val parsed = text.replace(',', '.').toBigDecimalOrNull()
                    onFieldChange { copy(amount = parsed) }
                },
                label = { Text(Strings.AMOUNT) },
                trailingIcon = { TextButton(onClick = { calculating = true }) { Text(Strings.CALCULATOR) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PickerField(
                label = Strings.OPERATION_TYPE,
                selectedText = dataset.operationType(draft.typeId)?.name ?: "—",
                items = dataset.operationTypes.filter { !it.deleted },
                itemText = { it.name ?: "id:${it.id}" },
                onSelect = { onFieldChange { copy(typeId = it.id) } },
            )
            BudgetItemShortcuts(dataset) { item -> onFieldChange { copy(budgetItemId = item.id) } }
            PickerField(
                label = Strings.BUDGET_ITEM,
                selectedText = budgetItemLabel(dataset, draft.budgetItemId),
                items = dataset.budgetItems.filter { !it.deleted },
                itemText = { budgetItemLabel(dataset, it.id) },
                onSelect = { onFieldChange { copy(budgetItemId = it.id) } },
            )
            if (formState.accountRules.fromEnabled) {
                PickerField(
                    label = Strings.ACCOUNT_FROM,
                    selectedText = dataset.account(draft.accountFrom)?.name ?: "—",
                    items = dataset.accounts.filter { !it.deleted },
                    itemText = { it.name ?: "id:${it.id}" },
                    onSelect = { onFieldChange { copy(accountFrom = it.id) } },
                )
            }
            if (formState.accountRules.toEnabled) {
                PickerField(
                    label = Strings.ACCOUNT_TO,
                    selectedText = dataset.account(draft.accountTo)?.name ?: "—",
                    items = dataset.accounts.filter { !it.deleted },
                    itemText = { it.name ?: "id:${it.id}" },
                    onSelect = { onFieldChange { copy(accountTo = it.id) } },
                )
            }
            PickerField(
                label = Strings.COUNTERPARTY,
                selectedText = dataset.counterparty(draft.counterpartyId)?.name ?: "—",
                items = dataset.counterparties.filter { !it.deleted },
                itemText = { it.name ?: "id:${it.id}" },
                onSelect = { onFieldChange { copy(counterpartyId = it.id) } },
                clearable = true,
                onClear = { onFieldChange { copy(counterpartyId = null) } },
            )
            OutlinedTextField(
                value = draft.date?.let(Formatter::date) ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(Strings.DATE) },
                trailingIcon = {
                    TextButton(onClick = { onFieldChange { copy(date = LocalDate.now()) } }) { Text("Сегодня") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.comment ?: "",
                onValueChange = { onFieldChange { copy(comment = it.ifBlank { null }) } },
                label = { Text(Strings.COMMENT) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (saveError != null) {
                Text(saveError!!, color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onSaveAndContinue != null) {
                OutlinedButton(onClick = { trySave(onSaveAndContinue) }, modifier = Modifier.weight(1f)) {
                    Text(Strings.SAVE)
                }
            }
            Button(onClick = { trySave(onSaveAndClose) }, modifier = Modifier.weight(1f)) {
                Text(if (onSaveAndContinue != null) Strings.SAVE_AND_CLOSE else Strings.SAVE)
            }
        }
    }
}

private fun budgetItemLabel(dataset: Dataset, id: Long?): String {
    if (id == null) return "—"
    val path = BudgetTree(dataset.budgetItems).path(id).mapNotNull { it.name }
    return path.joinToString(" → ").ifEmpty { dataset.budgetItem(id)?.name ?: "id:$id" }
}

/** FR-A3's «недавние» and «частые» rows of budget items — see [recentBudgetItems]/[frequentBudgetItems]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BudgetItemShortcuts(dataset: Dataset, onPick: (BudgetItem) -> Unit) {
    val recent = remember(dataset) { recentBudgetItems(dataset) }
    val frequent = remember(dataset) { frequentBudgetItems(dataset, exclude = recent.mapTo(HashSet()) { it.id }) }
    for ((caption, items) in listOf("Недавние" to recent, "Частые" to frequent)) {
        if (items.isEmpty()) continue
        Text(caption, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in items) {
                SuggestionChip(onClick = { onPick(item) }, label = { Text(item.name ?: "id:${item.id}") })
            }
        }
    }
}

/** A read-only field that opens a dropdown of [items] on tap — same idea as the journal filter
 * row's [MultiSelectFilterChip], reused here as a single-select form field. */
@Composable
internal fun <T> PickerField(
    label: String,
    selectedText: String,
    items: List<T>,
    itemText: (T) -> String,
    onSelect: (T) -> Unit,
    clearable: Boolean = false,
    onClear: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                if (clearable && selectedText != "—") {
                    IconButton(onClick = onClear) { Text("×") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only OutlinedTextField has no tap callback of its own — a transparent overlay
        // the same size as the field, drawn on top, opens the dropdown instead.
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(itemText(item)) }, onClick = { onSelect(item); expanded = false })
            }
        }
    }
}
