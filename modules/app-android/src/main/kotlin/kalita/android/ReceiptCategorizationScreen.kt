// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kalita.agent.api.BuiltInReceiptDictionaryEntry
import kalita.agent.api.ReceiptCategoryRule
import kalita.agent.api.ReceiptDictionaryEntry
import kalita.core.Dataset
import kalita.core.OperationKind

internal fun receiptExpenseItems(dataset: Dataset) = dataset.budgetItems.filter { item ->
    val expense = dataset.operationTypes.filter { !it.deleted && it.kind == OperationKind.Expense }.mapNotNull { it.budgetRootId }.toSet()
    val other = dataset.operationTypes.filter { !it.deleted && it.kind != OperationKind.Expense }.mapNotNull { it.budgetRootId }.toSet()
    val seen = mutableSetOf<Long>()
    var current: kalita.core.BudgetItem? = item
    var inExpense = false
    var valid = true
    while (current != null) {
        val node = current
        if (node.deleted || !seen.add(node.id) || node.id in other) { valid = false; break }
        if (node.id in expense) inExpense = true
        val parent = node.parentId ?: break
        current = dataset.budgetItem(parent)
        if (current == null) valid = false
    }
    valid && inExpense
}

@Composable
fun ReceiptCategorizationScreen(dataset: Dataset, controller: AndroidAppController, readOnly: Boolean, onBack: () -> Unit) {
    val store = remember(controller) { controller.receiptCategorization() }
    var rules by remember(store) { mutableStateOf(emptyList<ReceiptCategoryRule>()) }
    var dictionary by remember(store) { mutableStateOf(emptyList<ReceiptDictionaryEntry>()) }
    var builtIn by remember(store) { mutableStateOf(emptyList<BuiltInReceiptDictionaryEntry>()) }
    var error by remember(store) { mutableStateOf<String?>(null) }
    var loaded by remember(store) { mutableStateOf(false) }
    fun reload() {
        runCatching { Triple(store.rules(), store.dictionary(), store.builtInDictionary()) }
            .onSuccess { (r, d, b) -> rules = r; dictionary = d; builtIn = b; loaded = true; error = null }
            .onFailure { loaded = false; error = it.message ?: it.javaClass.simpleName }
    }
    fun save(action: () -> Unit): Boolean {
        return runCatching(action).fold(onSuccess = { reload(); loaded }, onFailure = { error = it.message ?: it.javaClass.simpleName; false })
    }
    androidx.compose.runtime.LaunchedEffect(store) { reload() }
    var editRule by remember { mutableStateOf<Int?>(null) }
    var editEntry by remember { mutableStateOf<Int?>(null) }
    var editBuiltIn by remember { mutableStateOf<BuiltInReceiptDictionaryEntry?>(null) }
    val choices = remember(dataset) { receiptExpenseItems(dataset) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row { TextButton(onClick = onBack) { Text("Назад") }; Text("Правила и словарь чеков", style = MaterialTheme.typography.titleLarge) }
        if (error != null) {
            Text("Ошибка: $error", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { reload() }) { Text("Повторить") }
        }
        if (readOnly) Text("Только чтение")
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text("Правила (сверху вниз)", style = MaterialTheme.typography.titleMedium)
                if (!readOnly && loaded) TextButton(onClick = { editRule = -1 }) { Text("Добавить правило") }
            }
            items(rules.size) { i ->
                val rule = rules[i]
                Column {
                    Text("${i + 1}. ${rule.merchant ?: "Любой магазин"} / ${rule.contains ?: "Любой товар"} → ${dataset.budgetItem(rule.budgetItemId)?.name ?: "id:${rule.budgetItemId} (недоступна)"}")
                    if (!readOnly && loaded) Row {
                        TextButton(onClick = { editRule = i }) { Text("Изменить") }
                        TextButton(enabled = i > 0, onClick = { save { store.replaceRules(rules.toMutableList().apply { add(i - 1, removeAt(i)) }) } }) { Text("↑") }
                        TextButton(enabled = i < rules.lastIndex, onClick = { save { store.replaceRules(rules.toMutableList().apply { add(i + 1, removeAt(i)) }) } }) { Text("↓") }
                        TextButton(onClick = { save { store.replaceRules(rules.filterIndexed { index, _ -> index != i }) } }) { Text("Удалить") }
                    }
                    HorizontalDivider()
                }
            }
            item {
                Text("Свой словарь", style = MaterialTheme.typography.titleMedium)
                if (!readOnly && loaded) TextButton(onClick = { editEntry = -1 }) { Text("Добавить слово") }
            }
            items(dictionary.size) { i ->
                val entry = dictionary[i]
                Column {
                    Text("${entry.text} → ${dataset.budgetItem(entry.budgetItemId)?.name ?: "id:${entry.budgetItemId} (недоступна)"}")
                    if (!readOnly && loaded) Row {
                        TextButton(onClick = { editEntry = i }) { Text("Изменить") }
                        TextButton(onClick = { save { store.replaceDictionary(dictionary.filterIndexed { index, _ -> index != i }) } }) { Text("Удалить") }
                    }
                    HorizontalDivider()
                }
            }
            item { Text("Встроенный словарь", style = MaterialTheme.typography.titleMedium) }
            items(builtIn.size) { i ->
                val entry = builtIn[i]
                Column {
                    Text("${entry.text} → ${entry.meaning ?: "отключено"} (по умолчанию: ${entry.defaultMeaning})")
                    if (!readOnly && loaded) Row {
                        TextButton(onClick = { editBuiltIn = entry }) { Text("Изменить") }
                        TextButton(onClick = { save { store.setBuiltInDictionaryEntry(entry.text, null) } }) { Text("Отключить") }
                        TextButton(onClick = { save { store.resetBuiltInDictionaryEntry(entry.text) } }) { Text("Сбросить") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    val ri = editRule
    val di = editEntry
    if (ri != null || di != null) {
        val rule = ri?.let { rules.getOrNull(it) }
        val entry = di?.let { dictionary.getOrNull(it) }
        var merchant by remember(ri, di) { mutableStateOf(rule?.merchant.orEmpty()) }
        var text by remember(ri, di) { mutableStateOf(rule?.contains ?: entry?.text.orEmpty()) }
        var id by remember(ri, di) { mutableStateOf(rule?.budgetItemId ?: entry?.budgetItemId) }
        var validation by remember(ri, di) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editRule = null; editEntry = null }, title = { Text(if (ri != null) "Правило" else "Слово") }, text = {
            Column {
                if (ri != null) OutlinedTextField(merchant, { merchant = it }, label = { Text("Магазин (точное совпадение)") })
                OutlinedTextField(text, { text = it }, label = { Text(if (ri != null) "Текст товара содержит" else "Текст товара") })
                BudgetItemField("Статья расходов", choices.firstOrNull { it.id == id }?.name ?: if (id == null) "Выберите статью" else "Недоступна: $id", choices, id, { id = it.id })
                if (validation != null) Text(validation!!, color = MaterialTheme.colorScheme.error)
                if (error != null) Text("Ошибка: $error", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            Button(onClick = {
                if ((ri != null && merchant.isBlank() && text.isBlank()) || (di != null && text.isBlank()) || choices.none { it.id == id }) {
                    validation = "Укажите условие и действующую статью расходов"
                } else {
                    val ok = if (ri != null) save {
                        val value = ReceiptCategoryRule(merchant.trim().ifEmpty { null }, text.trim().ifEmpty { null }, id!!)
                        store.replaceRules(rules.toMutableList().apply { if (ri < 0) add(value) else set(ri, value) })
                    } else save {
                        val value = ReceiptDictionaryEntry(text.trim(), id!!)
                        store.replaceDictionary(dictionary.toMutableList().apply { if (di!! < 0) add(value) else set(di, value) })
                    }
                    if (ok) { editRule = null; editEntry = null }
                }
            }) { Text("Сохранить") }
        }, dismissButton = { TextButton(onClick = { editRule = null; editEntry = null }) { Text("Отмена") } })
    }
    editBuiltIn?.let { entry ->
        var meaning by remember(entry) { mutableStateOf(entry.meaning ?: entry.defaultMeaning) }
        var validation by remember(entry) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editBuiltIn = null }, title = { Text(entry.text) }, text = {
            Column {
                OutlinedTextField(meaning, { meaning = it }, label = { Text("Название статьи") }, modifier = Modifier.fillMaxWidth())
                BudgetItemField("Выбрать статью", choices.firstOrNull { it.name == meaning }?.name ?: "Выберите статью", choices, choices.firstOrNull { it.name == meaning }?.id, { meaning = it.name.orEmpty() })
                if (validation) Text("Введите название", color = MaterialTheme.colorScheme.error)
                if (error != null) Text("Ошибка: $error", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { Button(onClick = {
            if (meaning.isBlank()) validation = true else if (save { store.setBuiltInDictionaryEntry(entry.text, meaning.trim()) }) editBuiltIn = null
        }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { editBuiltIn = null }) { Text("Отмена") } })
    }
}