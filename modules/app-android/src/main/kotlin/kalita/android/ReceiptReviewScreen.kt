// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kalita.agent.api.ReceiptProposal
import kalita.agent.api.ReceiptReviewModel
import kalita.agent.api.budgetPath
import kalita.core.Dataset
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The receipt review window (dev_plan.md §12.19) on Android: the same [ReceiptReviewModel] the
 * desktop dialog binds to Qt, here bound to Compose. One card per receipt line (text, amount, budget
 * item, what the agent had picked), the running sum against the receipt total, the paying account,
 * date, currency and the «merge by item» switch. «Сохранить в базу» → [onSave]; «Отклонить» → [onReject]
 * with an optional reason; «Позже» → [onLater] leaves the receipt in the queue.
 *
 * [ReceiptReviewModel] is a plain mutable object (no Compose state), so [tick] is bumped after each
 * edit to recompose everything that reads it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptReviewScreen(
    dataset: Dataset,
    proposal: ReceiptProposal,
    readOnly: Boolean,
    onSave: (ReceiptReviewModel) -> SubmitResult,
    onReject: (String?) -> Unit,
    onLater: () -> Unit,
) {
    val model = remember(proposal.id) { ReceiptReviewModel(proposal, dataset).also { if (it.date == null) it.date = LocalDate.now() } }
    var tick by remember(proposal.id) { mutableIntStateOf(0) }
    fun touch() { tick++ }
    check(tick >= 0) // the read subscribes this composable: it recomposes on every edit

    val accounts = remember(dataset) { dataset.accounts.filter { !it.deleted } }
    val currencies = remember(dataset) { dataset.currencies.filter { !it.deleted } }
    val choices = remember(proposal.id) { model.itemChoices() }
    var saveError by remember(proposal.id) { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf(false) }

    val problems = model.problems()
    val total = model.linesTotal()
    val diff = model.difference()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Strings.RECEIPT_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onLater) { Text(Strings.RECEIPT_LATER) }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            proposal.note?.takeIf { it.isNotBlank() }?.let { Text("Агент: $it", style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = model.merchant.orEmpty(),
                onValueChange = { model.merchant = it; touch() },
                label = { Text(Strings.RECEIPT_MERCHANT) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.date?.let(Formatter::date).orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(Strings.RECEIPT_DATE) },
                trailingIcon = { TextButton(onClick = { pickingDate = true }) { Text("Выбрать") } },
                modifier = Modifier.fillMaxWidth(),
            )
            PickerField(
                label = Strings.RECEIPT_ACCOUNT,
                selectedText = accounts.firstOrNull { it.id == model.accountFromId }?.name ?: "—",
                items = accounts,
                itemText = { it.name ?: "id:${it.id}" },
                onSelect = { model.accountFromId = it.id; touch() },
            )
            PickerField(
                label = Strings.RECEIPT_CURRENCY,
                selectedText = currencies.firstOrNull { it.id == model.currencyId }?.name ?: "—",
                items = currencies,
                itemText = { it.name ?: "id:${it.id}" },
                onSelect = { model.currencyId = it.id; touch() },
            )

            model.rows.forEachIndexed { index, row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                            OutlinedTextField(
                                value = row.text,
                                onValueChange = { row.text = it; touch() },
                                label = { Text(Strings.RECEIPT_COL_TEXT) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Raw text is kept while typing ("12," must not collapse to "12"): the field is
                        // keyed on the row's own amount only when something else changes it.
                        var amountText by remember(proposal.id, row) { mutableStateOf(row.amount?.let { Formatter.amount(it) }.orEmpty()) }
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = {
                                amountText = it
                                row.amount = it.replace(" ", "").replace(',', '.').trim().toBigDecimalOrNull()
                                touch()
                            },
                            label = { Text(Strings.RECEIPT_COL_AMOUNT) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PickerField(
                            label = Strings.RECEIPT_COL_ITEM,
                            selectedText = choices.firstOrNull { it.first == row.budgetItemId }?.second ?: Strings.RECEIPT_NO_ITEM,
                            items = choices,
                            itemText = { it.second },
                            onSelect = { row.budgetItemId = it.first; touch() },
                        )
                        val origin = row.originIndex?.let { proposal.lines.getOrNull(it) }
                        val agentText = if (origin == null) Strings.RECEIPT_AGENT_ADDED else budgetPath(dataset, origin.budgetItemId) ?: "—"
                        Text(
                            "${Strings.RECEIPT_COL_AGENT}: $agentText",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (model.changedByPerson(row)) FontWeight.Bold else FontWeight.Normal,
                            color = if (model.changedByPerson(row)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { model.rows.removeAt(index); touch() }) { Text(Strings.RECEIPT_REMOVE_LINE) }
                    }
                }
            }
            OutlinedButton(onClick = { model.addRow(); touch() }, modifier = Modifier.fillMaxWidth()) { Text(Strings.RECEIPT_ADD_LINE) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = model.mergeByItem, onCheckedChange = { model.mergeByItem = it; touch() })
                Text(Strings.RECEIPT_MERGE, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                buildString {
                    append("Итого по строкам: ${Formatter.amount(total)}")
                    proposal.total?.let { append(" · По чеку: ${Formatter.amount(it)}") }
                    if (diff != null) append(if (diff.signum() == 0) " · ✓ сходится" else " · расхождение ${Formatter.amount(diff)}")
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (diff != null && diff.signum() != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            val shown = saveError ?: problems.joinToString("\n").ifEmpty { null }
            if (shown != null) Text(shown, color = MaterialTheme.colorScheme.error)
            if (readOnly) Text("База открыта только для чтения — сохранить нельзя", color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { rejecting = true }, modifier = Modifier.weight(1f)) { Text(Strings.RECEIPT_REJECT) }
            Button(
                enabled = problems.isEmpty() && !readOnly,
                onClick = {
                    when (val result = onSave(model)) {
                        is SubmitResult.Applied -> saveError = null
                        is SubmitResult.Failed -> saveError = result.reason
                        is SubmitResult.Conflicts -> saveError = "База изменилась, повторите: ${result.conflicts.size} конфликт(ов)"
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(Strings.RECEIPT_SAVE) }
        }
    }

    if (pickingDate) {
        val initial = (model.date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { model.date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); touch() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(Strings.CANCEL) } },
        ) { DatePicker(state = pickerState) }
    }
    if (rejecting) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejecting = false },
            title = { Text(Strings.RECEIPT_REJECT_REASON_TITLE) },
            text = {
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text(Strings.RECEIPT_REJECT_REASON) })
            },
            confirmButton = { TextButton(onClick = { rejecting = false; onReject(reason) }) { Text(Strings.RECEIPT_REJECT) } },
            dismissButton = { TextButton(onClick = { rejecting = false }) { Text(Strings.CANCEL) } },
        )
    }
}
