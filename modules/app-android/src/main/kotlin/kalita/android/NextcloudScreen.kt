// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kalita.presentation.Strings
import kalita.sync.LoginFlowSession
import kalita.sync.LoginFlowV2
import kalita.sync.NextcloudClient
import kalita.sync.NextcloudCredentials
import kalita.sync.NextcloudEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which action this screen's Nextcloud setup dialog is walking the user through (Android's equivalent of the desktop menu items). */
enum class NextcloudSetupMode { MoveToNextcloud, OpenFromNextcloud }

/**
 * FR-S1/FR-S2's Login Flow v2 walk, in three steps: server address → open the login page in the
 * phone's browser and poll (every 2 s, the same interval the desktop dialog uses) until the user
 * finishes logging in there → the remote path. Mirrors `app-desktop`'s `NextcloudDialogs.kt`
 * exactly in protocol terms, just as Compose dialogs instead of Qt ones. [onReady] fires once with
 * the credentials and path; [onDismiss] can fire from any step (the user backing out).
 */
@Composable
fun NextcloudSetupDialog(onDismiss: () -> Unit, onReady: (NextcloudCredentials, String) -> Unit) {
    var step by remember { mutableStateOf(0) } // 0 = server, 1 = waiting for the browser, 2 = remote path
    var server by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<LoginFlowSession?>(null) }
    var credentials by remember { mutableStateOf<NextcloudCredentials?>(null) }
    var remotePath by remember { mutableStateOf("/Data.mdb") }
    var currentFolder by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<NextcloudEntry>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    when (step) {
        0 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nextcloud") },
            text = {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Адрес сервера (например, https://cloud.example.com)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val started = withContext(Dispatchers.IO) { runCatching { LoginFlowV2().start(server.trim()) } }
                        started.onSuccess {
                            session = it
                            context.startActivity(Intent(Intent.ACTION_VIEW, it.loginUrl.toUri()))
                            step = 1
                        }.onFailure { error = it.message }
                    }
                }) { Text("Далее") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
        )
        1 -> {
            LaunchedEffect(session) {
                val activeSession = session ?: return@LaunchedEffect
                val flow = LoginFlowV2()
                while (isActive) {
                    val result = withContext(Dispatchers.IO) { runCatching { flow.poll(activeSession) }.getOrNull() }
                    if (result != null) {
                        credentials = result
                        step = 2
                        break
                    }
                    delay(2000)
                }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Вход в Nextcloud") },
                text = { Text("Завершите вход в открывшейся странице браузера…") },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
            )
        }
        2 -> {
            // Browsing the server's own listing (PROPFIND Depth: 1) instead of a bare text field —
            // the path field below stays editable throughout (unlike the list, it never fails),
            // since FR-S1's "Переместить базу в Nextcloud" needs a path that does not exist on the
            // server yet, which no listing would ever show as a pickable entry.
            LaunchedEffect(credentials, currentFolder) {
                val creds = credentials ?: return@LaunchedEffect
                val client = NextcloudClient(creds.server, creds.loginName, creds.appPassword)
                val result = withContext(Dispatchers.IO) { runCatching { client.list(currentFolder) } }
                result.onSuccess { entries = it.sortedWith(compareBy({ !it.isFolder }, { it.path.lowercase() })); listError = null }
                    .onFailure { entries = emptyList(); listError = it.message }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Nextcloud") },
                text = {
                    Column {
                        Row {
                            Text("/$currentFolder", modifier = Modifier.padding(bottom = 4.dp))
                        }
                        if (currentFolder.isNotEmpty()) {
                            TextButton(onClick = { currentFolder = currentFolder.substringBeforeLast('/', "") }) {
                                Text("⬆ Вверх")
                            }
                        }
                        listError?.let { Text("Не удалось получить список: $it") }
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(entries) { entry ->
                                val name = entry.path.substringAfterLast('/')
                                Text(
                                    (if (entry.isFolder) "📁 " else "📄 ") + name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (entry.isFolder) currentFolder = entry.path else remotePath = "/${entry.path}"
                                        }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = remotePath,
                            onValueChange = { remotePath = it },
                            label = { Text("Путь к файлу базы на сервере") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { credentials?.let { onReady(it, remotePath.trim()) } }) { Text(Strings.SAVE) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
            )
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Ошибка") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}
