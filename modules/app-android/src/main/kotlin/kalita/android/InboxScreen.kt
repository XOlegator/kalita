// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kalita.plugin.api.BankTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun InboxScreen(controller: AndroidAppController, readOnly: Boolean, onBack: () -> Unit, onReconcile: () -> Unit) {
    var transactions by remember { mutableStateOf<List<BankTransaction>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var agents by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var recovering by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var agentError by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        runCatching { controller.pendingStatements() }
            .onSuccess { transactions = it; error = null }
            .onFailure { transactions = emptyList(); error = it.message ?: it.toString() }
        runCatching { controller.agentInbox() }
            .onSuccess { (pending, applying) ->
                agents = pending.getValue("entries").jsonArray.map { it.jsonObject }
                recovering = applying.getValue("entries").jsonArray.map { it.jsonObject }
                agentError = null
            }
            .onFailure { agents = emptyList(); recovering = emptyList(); agentError = it.message ?: it.toString() }
    }
    fun process(id: String, action: String) {
        scope.launch {
            busy = true
            val result = runCatching { withContext(Dispatchers.IO) { controller.processAgentInbox(id, action) } }
            refresh()
            result.onSuccess {
                if (it["ok"]?.jsonPrimitive?.booleanOrNull != true) agentError = it.toString()
            }.onFailure { agentError = it.message ?: it.toString() }
            busy = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(controller) { refresh() }
    confirmation?.let { (id, action) ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Проверка операции") },
            text = { Text(if (action == "committed" || action == "notCommitted")
                "Проверьте журнал базы перед восстановлением. Неверный выбор может привести к повторной операции. Вы уверены?"
                else "Выполнить выбранное действие с черновиком?") },
            confirmButton = { TextButton(onClick = { confirmation = null; process(id, action) }) { Text("Подтвердить") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Отмена") } },
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Входящие")
        TextButton(onClick = onBack) { Text("Назад") }
        TextButton(onClick = { refresh() }) { Text("Обновить") }
        Text("Агентские черновики")
        Text("Очередь хранится на этом устройстве и не синхронизируется с Nextcloud.")
        agentError?.let { Text(it) }
        if (agentError == null && agents.isEmpty() && recovering.isEmpty()) Text("Нет черновиков")
        (agents.map { it to false } + recovering.map { it to true }).forEach { (entry, recovery) ->
            HorizontalDivider()
            fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            val preview = entry.getValue("preview").jsonObject
            Text("${entry.text("createdAt")} · ${entry.text("device")} · ${entry.text("clientRequestId")}")
            Text("ID: ${entry.text("id")}")
            listOf("date" to "Дата", "amount" to "Сумма", "currency" to "Валюта", "operationType" to "Тип", "budgetItem" to "Статья", "counterparty" to "Контрагент", "accountFrom" to "Со счёта", "accountTo" to "На счёт", "comment" to "Комментарий").forEach { (key, label) ->
                Text("$label: ${preview.text(key)}")
            }
            if (recovery) Text("Результат подтверждения неизвестен. Проверьте журнал базы перед восстановлением.")
            if (!readOnly) {
                if (recovery) {
                    TextButton(enabled = !busy, onClick = { confirmation = entry.text("id") to "committed" }) { Text("Операция записана") }
                    TextButton(enabled = !busy, onClick = { confirmation = entry.text("id") to "notCommitted" }) { Text("Операция не записана") }
                } else {
                    Button(enabled = !busy, onClick = { confirmation = entry.text("id") to "approve" }) { Text("Подтвердить") }
                    TextButton(enabled = !busy, onClick = { confirmation = entry.text("id") to "reject" }) { Text("Отклонить") }
                }
            }
        }
        Text("Ожидающие банковские строки")
        error?.let { Text(it) }
        if (error == null && transactions.isEmpty()) Text("Нет ожидающих строк")
        transactions.forEach { tx ->
            HorizontalDivider()
            Text("${tx.postedAt} · ${tx.amount} ${tx.currency}")
            Text("${tx.bankAccount} · ${tx.description}")
        }
        if (transactions.isNotEmpty() && !readOnly) {
            Button(onClick = onReconcile) { Text("Обработать выписки") }
        }
    }
}