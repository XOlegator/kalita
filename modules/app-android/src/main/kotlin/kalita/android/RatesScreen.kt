// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kalita.core.Currency
import kalita.core.Rate
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * «Курсы валют» on the phone (dev_plan.md FR-12/FR-R12): what is stored, newest first, narrowed
 * to one currency by a chip, and «Загрузить из Интернета» with the period chosen in one tap.
 * Editing a single rate by hand stays on the computer — the phone's job here is to see that the
 * rates are there and to fetch the missing ones.
 */
@Composable
fun RatesScreen(
    currencies: List<Currency>,
    rates: List<Rate>,
    baseCurrencyId: Long?,
    readOnly: Boolean,
    sourceName: String,
    onBack: () -> Unit,
    onLoad: suspend (from: LocalDate, to: LocalDate) -> String,
    today: LocalDate = LocalDate.now(),
) {
    BackHandler(onBack = onBack)
    var currencyFilter by remember { mutableStateOf<Long?>(null) }
    var showLoad by remember { mutableStateOf(false) }
    var lastMessage by remember { mutableStateOf<String?>(null) }
    val names = currencies.associate { it.id to (it.name ?: "id:${it.id}") }
    val quoted = currencies.filter { !it.deleted && it.id != baseCurrencyId }
    val shown = rates.filter { !it.deleted && it.date != null && (currencyFilter == null || it.currencyId == currencyFilter) }
        .sortedWith(compareByDescending<Rate> { it.date }.thenBy { names[it.currencyId] })

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Strings.RATES_TITLE, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Назад") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = currencyFilter == null, onClick = { currencyFilter = null }, label = { Text(Strings.ALL) })
            for (currency in quoted) {
                FilterChip(
                    selected = currencyFilter == currency.id,
                    onClick = { currencyFilter = if (currencyFilter == currency.id) null else currency.id },
                    label = { Text(names.getValue(currency.id)) },
                )
            }
        }
        if (!readOnly) {
            Button(onClick = { showLoad = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(Strings.RATES_FROM_INTERNET)
            }
        }
        lastMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        HorizontalDivider()
        if (shown.isEmpty()) {
            Text(Strings.RATES_EMPTY, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { rate ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${Formatter.date(rate.date!!)}   ${names[rate.currencyId] ?: "—"}")
                        Text(rate.value?.let { Formatter.rate(BigDecimal.valueOf(it)) } ?: "—")
                    }
                }
            }
        }
    }

    if (showLoad) {
        RateLoadDialog(
            rates = rates,
            currencyIds = quoted.mapTo(HashSet()) { it.id },
            sourceName = sourceName,
            today = today,
            onDismiss = { showLoad = false },
            onLoad = onLoad,
            onDone = { message ->
                lastMessage = message
                showLoad = false
            },
        )
    }
}

/** The period choice and the load itself; stays open with a spinner while the request is out. */
@Composable
private fun RateLoadDialog(
    rates: List<Rate>,
    currencyIds: Set<Long>,
    sourceName: String,
    today: LocalDate,
    onDismiss: () -> Unit,
    onLoad: suspend (LocalDate, LocalDate) -> String,
    onDone: (String) -> Unit,
) {
    val sinceLast = ratePeriod(RatePeriodChoice.SinceLast, today, rates, currencyIds)
    var choice by remember { mutableStateOf(RatePeriodChoice.Today) }
    var customFrom by remember { mutableStateOf(today.minusDays(6)) }
    var customTo by remember { mutableStateOf(today) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val period = if (choice == RatePeriodChoice.Custom) customFrom to customTo else ratePeriod(choice, today, rates, currencyIds)
    val valid = period != null && !period.first.isAfter(period.second)

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(Strings.RATE_LOAD_TITLE) },
        text = {
            Column {
                Text(sourceName, style = MaterialTheme.typography.bodySmall)
                PeriodOption(Strings.RATES_PERIOD_TODAY, choice == RatePeriodChoice.Today, !loading) { choice = RatePeriodChoice.Today }
                PeriodOption(Strings.RATES_PERIOD_WEEK, choice == RatePeriodChoice.Week, !loading) { choice = RatePeriodChoice.Week }
                PeriodOption(Strings.RATES_PERIOD_MONTH, choice == RatePeriodChoice.Month, !loading) { choice = RatePeriodChoice.Month }
                if (sinceLast != null) {
                    PeriodOption(Strings.ratesPeriodSinceLast(Formatter.date(sinceLast.first)), choice == RatePeriodChoice.SinceLast, !loading) {
                        choice = RatePeriodChoice.SinceLast
                    }
                }
                PeriodOption(Strings.RATES_PERIOD_CUSTOM, choice == RatePeriodChoice.Custom, !loading) { choice = RatePeriodChoice.Custom }
                if (choice == RatePeriodChoice.Custom) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateButton(Strings.FROM_DATE, customFrom, !loading) { customFrom = it }
                        DateButton(Strings.TO_DATE, customTo, !loading) { customTo = it }
                    }
                }
                if (period != null && choice != RatePeriodChoice.Custom) {
                    Text(
                        "${Formatter.date(period.first)} — ${Formatter.date(period.second)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(
                    enabled = valid,
                    onClick = {
                        val (from, to) = period ?: return@TextButton
                        loading = true
                        scope.launch { onDone(onLoad(from, to)) }
                    },
                ) { Text(Strings.RATE_LOAD_BUTTON) }
            }
        },
        dismissButton = { TextButton(enabled = !loading, onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

@Composable
private fun PeriodOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(label)
    }
}

/** A date shown on a button; tapping opens the system-style calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateButton(label: String, date: LocalDate, enabled: Boolean, onPicked: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(enabled = enabled, onClick = { open = true }) { Text("$label ${Formatter.date(date)}") }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(Strings.CANCEL) } },
        ) { DatePicker(state = state) }
    }
}
