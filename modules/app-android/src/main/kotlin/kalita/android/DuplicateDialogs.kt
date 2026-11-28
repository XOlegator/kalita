// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kalita.core.Dataset
import kalita.core.DuplicateAction
import kalita.core.DuplicateCheckMode
import kalita.core.DuplicateCheckOptions
import kalita.core.DuplicateSession
import kalita.core.DuplicateScan
import kalita.core.DuplicateSource
import kalita.presentation.DuplicateTexts
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** How a [DuplicateResolveDialog] pass ended. */
enum class DuplicateSessionResult { APPLIED, LATER, CANCELLED }

/**
 * The universal duplicate-resolution window on Android: one [DuplicateSession] finding at a time,
 * a button per available answer (a «оставить старую» answer fills the suggested fields in). The
 * caller reads [DuplicateSession.plan] once [DuplicateSessionResult.APPLIED] comes back.
 */
@Composable
internal fun DuplicateResolveDialog(
    session: DuplicateSession,
    dataset: Dataset,
    onFinished: (DuplicateSessionResult) -> Unit,
) {
    var finding by remember { mutableStateOf(session.next()) }
    AlertDialog(
        onDismissRequest = { onFinished(DuplicateSessionResult.CANCELLED) },
        title = { Text(Strings.DUPLICATES_TITLE) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val current = finding
                if (current != null) {
                    Text(DuplicateTexts.finding(dataset, session.candidates, current))
                    Text(Strings.DUPLICATES_QUESTION, style = MaterialTheme.typography.titleSmall)
                    val changes = session.changes(current)
                    if (changes.isNotEmpty()) {
                        Text(Strings.DUPLICATES_ENRICH, style = MaterialTheme.typography.bodySmall)
                        changes.forEach { change ->
                            Text(
                                (if (change.suggested) "✓ " else "  · ") + DuplicateTexts.change(dataset, change),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column {
                val actions = finding?.let { session.actions(it).filterNot { a -> a == DuplicateAction.DISCARD } }.orEmpty()
                for (action in actions) {
                    TextButton(onClick = {
                        val current = finding ?: return@TextButton
                        session.decide(current, action)
                        finding = session.next()
                        if (finding == null) onFinished(DuplicateSessionResult.APPLIED)
                    }) { Text(Strings.duplicateActionLabel(action)) }
                }
                if (actions.contains(DuplicateAction.LATER)) {
                    TextButton(onClick = { onFinished(DuplicateSessionResult.LATER) }) { Text(Strings.RECEIPT_LATER) }
                }
            }
        },
        dismissButton = { TextButton(onClick = { onFinished(DuplicateSessionResult.CANCELLED) }) { Text(Strings.CANCEL) } },
    )
}

/**
 * «Контроль дублей…» on Android: the mode setting (обязательная проверка или по кнопке), the
 * amount tolerance and the days window, and the whole-journal check over a period of the
 * person's choice.
 */
@Composable
internal fun DuplicateCheckScreen(controller: AndroidAppController, dataset: Dataset, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val options = remember { controller.duplicateOptions() }
    var mode by remember { mutableStateOf(options.mode) }
    var tolerance by remember { mutableStateOf(options.amountTolerance.stripTrailingZeros().toPlainString()) }
    var days by remember { mutableStateOf(options.daysWindow.toString()) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }

    var from by remember { mutableStateOf(LocalDate.now().minusMonths(1)) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var pickingFrom by remember { mutableStateOf(false) }
    var pickingTo by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<DuplicateScan?>(null) }
    var hiddenGroups by remember { mutableStateOf(setOf<Int>()) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(Strings.DUPLICATES_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(Strings.CANCEL) }
        }

        PickerField(
            label = Strings.DUPLICATES_MODE,
            selectedText = if (mode == DuplicateCheckMode.ON_SAVE) Strings.DUPLICATES_MODE_ON_SAVE else Strings.DUPLICATES_MODE_MANUAL,
            items = DuplicateCheckMode.entries,
            itemText = { if (it == DuplicateCheckMode.ON_SAVE) Strings.DUPLICATES_MODE_ON_SAVE else Strings.DUPLICATES_MODE_MANUAL },
            onSelect = { mode = it },
        )
        OutlinedTextField(
            value = tolerance,
            onValueChange = { tolerance = it },
            label = { Text(Strings.DUPLICATES_TOLERANCE) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = days,
            onValueChange = { days = it },
            label = { Text(Strings.DUPLICATES_DAYS) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            val parsedTolerance = tolerance.replace(',', '.').trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
            val parsedDays = days.trim().toIntOrNull()?.takeIf { it in 0..31 }
            if (parsedTolerance == null || parsedDays == null) {
                settingsMessage = "Допуск — число от 0; окно — дни от 0 до 31"
            } else {
                runCatching { controller.setDuplicateOptions(DuplicateCheckOptions(mode, parsedTolerance, parsedDays)) }
                    .onSuccess { settingsMessage = "Настройки сохранены" }
                    .onFailure { settingsMessage = it.message ?: it.toString() }
            }
        }) { Text(Strings.DUPLICATES_SAVE) }
        settingsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(Strings.DUPLICATES_SCAN_MENU, style = MaterialTheme.typography.titleMedium)
        DuplicateDateField("С", from) { from = it }
        DuplicateDateField("По", to) { to = it }
        Button(
            enabled = !scanning && !from.isAfter(to),
            onClick = {
                scanning = true
                scope.launch(Dispatchers.Default) {
                    scan = controller.scanDuplicates(from, to)
                    hiddenGroups = emptySet()
                    scanning = false
                }
            },
        ) { Text(if (scanning) "Проверяю…" else Strings.DUPLICATES_SCAN) }

        val result = scan
        if (result != null) {
            val shown = result.groups.withIndex().filter { it.index !in hiddenGroups }
            if (shown.isEmpty()) {
                Text(Strings.DUPLICATES_NONE + if (result.truncated) " " + Strings.DUPLICATES_TRUNCATED else "", modifier = Modifier.padding(vertical = 8.dp))
            } else {
                Text(Strings.duplicatesFound(shown.size) + if (result.truncated) " " + Strings.DUPLICATES_TRUNCATED else "", modifier = Modifier.padding(vertical = 8.dp))
            }
            for ((index, group) in shown) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        "${group.rows.firstOrNull()?.date?.let(Formatter::date) ?: "—"} — ${group.rows.lastOrNull()?.date?.let(Formatter::date) ?: ""}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    for (row in group.rows) {
                        Text(
                            DuplicateTexts.row(dataset, kalita.core.DuplicateOperation(row, false, false)) +
                                if (row.id in group.suggested) " ← ${Strings.DUPLICATES_LESS_DETAIL}" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    for (link in group.links) {
                        Text(
                            link.right.joinToString(" + ", "  #${link.left} = ") { "#$it" } + " · " + Strings.duplicateDiff(link.difference),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row {
                        val suggested = group.suggested.toList().sorted()
                        if (suggested.isNotEmpty()) {
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    runCatching { controller.softDeleteOperations(suggested) }
                                    scan = controller.scanDuplicates(from, to)
                                    hiddenGroups = emptySet()
                                }
                            }) { Text(Strings.duplicatesDeleteSuggested(suggested)) }
                        }
                        TextButton(onClick = { hiddenGroups = hiddenGroups + index }) { Text(Strings.DUPLICATES_KEEP) }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (pickingFrom) DuplicateDateDialog(from, { pickingFrom = false }) { from = it }
    if (pickingTo) DuplicateDateDialog(to, { pickingTo = false }) { to = it }
}

/** A read-only date field with the Material date picker — the same shape the receipt window uses. */
@Composable
private fun DuplicateDateField(label: String, value: LocalDate, onPick: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = Formatter.date(value),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = { TextButton(onClick = { picking = true }) { Text("Выбрать") } },
        modifier = Modifier.fillMaxWidth(),
    )
    if (picking) DuplicateDateDialog(value, { picking = false }, onPick)
}

@Composable
private fun DuplicateDateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    ) { DatePicker(state = pickerState) }
}
