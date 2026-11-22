// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kalita.core.BudgetItem
import kalita.presentation.Strings

/**
 * Full-screen budget item picker for the phone: a collapsible tree instead of a flat list of
 * long «A → B → C» paths. Built for a thumb — every row is at least 56 dp tall; the chevron at
 * the left is its own 56 dp target that only opens/closes the branch, the rest of the row picks
 * the item (any level may be picked, as on the desktop). Search shows matches with their parents.
 * The currently chosen item is highlighted and its branches start open and scrolled into view.
 */
@Composable
fun BudgetItemPickerDialog(
    items: List<BudgetItem>,
    selectedId: Long?,
    onSelect: (BudgetItem) -> Unit,
    onDismiss: () -> Unit,
    /** When set, a «Без статьи» row at the top lets the user clear the choice. */
    onClear: (() -> Unit)? = null,
) {
    BudgetItemTreeDialog(items, listOfNotNull(selectedId).toSet(), onSelect, onDismiss, onClear, multiSelect = false)
}

@Composable
fun BudgetItemMultiPickerDialog(
    items: List<BudgetItem>,
    selectedIds: Set<Long>,
    onChange: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    BudgetItemTreeDialog(
        items = items,
        selectedIds = selectedIds,
        onSelect = { onChange(BudgetPickerTree.toggle(selectedIds, it.id)) },
        onDismiss = onDismiss,
        onClear = null,
        multiSelect = true,
    )
}

@Composable
private fun BudgetItemTreeDialog(
    items: List<BudgetItem>,
    selectedIds: Set<Long>,
    onSelect: (BudgetItem) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)?,
    multiSelect: Boolean,
) {
    var expanded by remember { mutableStateOf(BudgetPickerTree.initiallyExpanded(items, selectedIds)) }
    var query by remember { mutableStateOf("") }
    val rows = remember(items, expanded, query) { BudgetPickerTree.rows(items, expanded, query) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val at = rows.indexOfFirst { it.item.id in selectedIds }
        if (at > 0) listState.scrollToItem(maxOf(0, at - 2))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Strings.BUDGET_ITEM, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (multiSelect) "Готово" else Strings.CANCEL) }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (onClear != null) {
                        item(key = "clear") {
                            Text(
                                Strings.BUDGET_ITEM_CLEAR,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClear)
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    items(rows, key = { it.item.id }) { row ->
                        BudgetPickerRowView(
                            row = row,
                            selected = row.item.id in selectedIds,
                            multiSelect = multiSelect,
                            onToggle = { expanded = BudgetPickerTree.toggle(expanded, row.item.id) },
                            onPick = { onSelect(row.item) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetPickerRowView(row: BudgetPickerRow, selected: Boolean, multiSelect: Boolean, onToggle: () -> Unit, onPick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).background(background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Indent by level, but cap it so deep trees stay readable on a narrow screen.
        Box(modifier = Modifier.size(width = (minOf(row.depth, 5) * 16).dp, height = 1.dp))
        Box(
            modifier = Modifier.size(56.dp)
                .then(if (row.hasChildren) Modifier.clickable(onClick = onToggle).semantics { contentDescription = if (row.expanded) "Свернуть" else "Развернуть" } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (row.hasChildren) Text(if (row.expanded) "▾" else "▸", style = MaterialTheme.typography.titleLarge)
        }
        if (multiSelect) Checkbox(checked = selected, onCheckedChange = { onPick() })
        Text(
            text = row.item.name ?: "id:${row.item.id}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (row.hasChildren) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClick = onPick)
                .padding(end = 16.dp).padding(vertical = 16.dp),
        )
    }
}

/** A read-only form field showing the chosen item's full path; a tap opens [BudgetItemPickerDialog]. */
@Composable
fun BudgetItemField(
    label: String,
    selectedText: String,
    items: List<BudgetItem>,
    selectedId: Long?,
    onSelect: (BudgetItem) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Text("▾", modifier = Modifier.padding(end = 12.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        BudgetItemPickerDialog(
            items = items,
            selectedId = selectedId,
            onSelect = { open = false; onSelect(it) },
            onDismiss = { open = false },
            onClear = onClear?.let { clear -> { open = false; clear() } },
        )
    }
}
