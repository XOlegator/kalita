// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kalita.core.Dataset
import kalita.core.JournalColumn
import kalita.core.JournalRow
import kalita.presentation.JournalRowFormatter

/**
 * dev_plan.md Этап 10 (FR-A1 local half, now including export and reopen-on-launch, + a
 * read-only FR-A2): pick a `.mdb` via the system file picker, open it through
 * [AndroidAppController], show its journal, export a copy back out. Replaces the
 * build→sign→install placeholder from the Этап 8 addendum.
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
                is AndroidAppState.Ready ->
                    JournalScreen(
                        dataset = state.dataset,
                        rows = journalState.rows,
                        readOnly = state.readOnly,
                        onExport = exportPicker,
                    )
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
private fun JournalScreen(dataset: Dataset, rows: List<JournalRow>, readOnly: Boolean, onExport: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Kalita", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onExport) { Text("Экспорт") }
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
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.operation.id }) { row ->
                JournalRowItem(dataset, row)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun JournalRowItem(dataset: Dataset, row: JournalRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "${JournalRowFormatter.cellText(dataset, row, JournalColumn.Date)}  ·  " +
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
