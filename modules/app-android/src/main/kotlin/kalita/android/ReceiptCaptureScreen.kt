// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kalita.agent.api.ReceiptImage
import kalita.agent.api.ReceiptProposal
import kalita.agent.api.RecognizerConfig
import kalita.agent.api.VisionProvider
import kalita.presentation.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_FRAMES = 10

/**
 * «Чек по фото»: take or pick one or several photos of one receipt (a long receipt is shot in parts),
 * confirm, and a vision model reads them into a proposal that opens in the review window. The photos
 * go to the service the person configured (their own key) — the screen says so — and nothing is written
 * to the database before the person confirms in the review window.
 */
@Composable
fun ReceiptCaptureScreen(
    config: RecognizerConfig?,
    onSaveConfig: (RecognizerConfig) -> Unit,
    onRecognize: suspend (List<ReceiptImage>) -> Result<ReceiptProposal>,
    onRecognized: (ReceiptProposal) -> Unit,
    onManualPrompt: (imageCount: Int) -> String?,
    onImportReply: suspend (String, List<ReceiptImage>) -> Result<ReceiptProposal>,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Strings, not Uris, so the list survives the Activity being recreated while the camera app is in front.
    val frames = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableListOf<String>().toMutableStateList() }
    var cameraTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = cameraTarget
        if (ok && target != null) frames += target else target?.let { runCatching { File(Uri.parse(it).path.orEmpty()).delete() } }
        cameraTarget = null
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_FRAMES)) { uris ->
        for (u in uris) if (frames.size < MAX_FRAMES && u.toString() !in frames) frames += u.toString()
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "receipt-shots").apply { mkdirs() }
        val file = File.createTempFile("receipt", ".jpg", dir)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraTarget = uri.toString()
        takePicture.launch(uri)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Чек по фото", style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = { showSettings = true }, enabled = !busy) { Text("Настройки") }
                TextButton(onClick = onCancel, enabled = !busy) { Text(Strings.CANCEL) }
            }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (config == null) {
                Text("Ключ сервиса не задан: можно читать чек через чат-приложение (блок «Без ключа» ниже) или настроить ключ.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Настроить ключ") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = ::launchCamera,
                    enabled = !busy && frames.size < MAX_FRAMES,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text("📷 Снять") }
                OutlinedButton(
                    onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy && frames.size < MAX_FRAMES,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text("🖼 Из галереи") }
            }
            if (frames.isEmpty()) {
                Text(
                    "Сфотографируйте чек или выберите снимки из галереи. Длинный чек снимайте по частям сверху вниз, " +
                        "чтобы соседние кадры немного перекрывались. Можно до $MAX_FRAMES кадров.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Кадров: ${frames.size} — по порядку сверху вниз", style = MaterialTheme.typography.labelLarge)
                // Fixed-height grid inside a scrolling column: rows of 3 thumbnails.
                val rows = (frames.size + 2) / 3
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height((rows * 136).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = false,
                ) {
                    items(frames.toList(), key = { it }) { frame ->
                        FrameThumbnail(frame, enabled = !busy, onRemove = { frames -= frame })
                    }
                }
            }
            HorizontalDivider()
            Text("Без ключа — через ваше чат-приложение", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. Нажмите «Отправить снимки и инструкцию» и выберите приложение (Claude и т. п.). Если приложение не приняло текст, " +
                    "он уже скопирован — вставьте его вручную. 2. Дождитесь ответа, скопируйте его целиком. 3. Нажмите «Вставить ответ» — откроется обычное окно проверки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    error = null
                    val prompt = onManualPrompt(frames.size)
                    if (prompt == null) { error = "Нет открытой базы"; return@OutlinedButton }
                    busy = true
                    val snapshot = frames.toList()
                    scope.launch {
                        val sent = runCatching {
                            val uris = withContext(Dispatchers.IO) { shareableUris(context, snapshot) }
                            copyToClipboard(context, prompt)
                            val intent = android.content.Intent(if (uris.size == 1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                type = if (uris.isEmpty()) "text/plain" else "image/*"
                                putExtra(android.content.Intent.EXTRA_TEXT, prompt)
                                if (uris.size == 1) putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                                else if (uris.size > 1) putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Отправить чек в…"))
                        }
                        busy = false
                        sent.onFailure { error = it.message ?: "Не удалось открыть выбор приложения" }
                    }
                },
                enabled = !busy && frames.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Отправить снимки и инструкцию") }
            OutlinedButton(
                onClick = {
                    error = null
                    val pasted = readClipboard(context)
                    if (pasted.isBlank()) { error = "Буфер обмена пуст — скопируйте ответ чата"; return@OutlinedButton }
                    busy = true
                    val snapshot = frames.toList()
                    scope.launch {
                        val prepared = runCatching { withContext(Dispatchers.IO) { snapshot.map { ReceiptImagePrep.prepare(context, Uri.parse(it)) } } }
                        val result = prepared.fold(onSuccess = { onImportReply(pasted, it) }, onFailure = { Result.failure(it) })
                        busy = false
                        result.onSuccess { frames.clear(); onRecognized(it) }.onFailure { error = it.message ?: "Ответ не разобран" }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Вставить ответ из буфера") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Снимки отправляются выбранному вам сервису распознавания. Записи в базу попадут только после вашей проверки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Button(
                onClick = {
                    error = null
                    busy = true
                    val snapshot = frames.toList()
                    scope.launch {
                        val prepared = runCatching { withContext(Dispatchers.IO) { snapshot.map { ReceiptImagePrep.prepare(context, Uri.parse(it)) } } }
                        val result = prepared.fold(onSuccess = { onRecognize(it) }, onFailure = { Result.failure(it) })
                        busy = false
                        result.onSuccess { frames.clear(); onRecognized(it) }.onFailure { error = it.message ?: "Не удалось распознать чек" }
                    }
                },
                enabled = !busy && frames.isNotEmpty() && config != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Распознаём…")
                } else {
                    Text(if (frames.isEmpty()) "Распознать" else "Распознать (${frames.size})")
                }
            }
        }
    }
    if (showSettings) {
        RecognizerSettingsDialog(config, onDismiss = { showSettings = false }, onSave = { onSaveConfig(it); showSettings = false })
    }
}

/** The prepared (rotated, scaled) frames as content URIs another app may read; they live in the cache. */
private fun shareableUris(context: android.content.Context, frames: List<String>): List<Uri> {
    val dir = File(context.cacheDir, "receipt-shots").apply { mkdirs() }
    dir.listFiles { f -> f.name.startsWith("share-") }?.forEach { it.delete() }
    return frames.mapIndexed { i, frame ->
        val file = File(dir, "share-${i + 1}.jpg")
        file.writeBytes(ReceiptImagePrep.prepare(context, Uri.parse(frame)).bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Kalita receipt prompt", text))
}

private fun readClipboard(context: android.content.Context): String {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

@Composable
private fun FrameThumbnail(frame: String, enabled: Boolean, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, frame) {
        value = withContext(Dispatchers.IO) { ReceiptImagePrep.thumbnail(context, Uri.parse(frame))?.asImageBitmap() }
    }
    Box(modifier = Modifier.size(128.dp)) {
        bitmap?.let { Image(it, contentDescription = "Кадр чека", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(24.dp))
        TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.align(Alignment.TopEnd)) { Text("✕") }
    }
}

@Composable
private fun RecognizerSettingsDialog(current: RecognizerConfig?, onDismiss: () -> Unit, onSave: (RecognizerConfig) -> Unit) {
    var provider by remember { mutableStateOf(current?.provider ?: VisionProvider.Anthropic) }
    var baseUrl by remember { mutableStateOf(current?.baseUrl ?: provider.defaultBaseUrl) }
    var model by remember { mutableStateOf(current?.model ?: provider.defaultModel) }
    var key by remember { mutableStateOf(current?.apiKey.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Распознавание чеков") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in VisionProvider.entries) {
                    FilterChip(
                        selected = provider == p,
                        onClick = {
                            if (provider != p) { provider = p; baseUrl = p.defaultBaseUrl; model = p.defaultModel }
                        },
                        label = { Text(p.label) },
                    )
                }
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Адрес API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Модель (с поддержкой изображений)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, label = { Text("Ключ API") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Ключ хранится в защищённом хранилище телефона. Подойдёт любой сервис, читающий изображения: Claude, " +
                        "OpenAI, OpenRouter и другие с OpenAI-совместимым API (модель без поддержки картинок, например текстовая " +
                        "DeepSeek, чек не прочитает).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(RecognizerConfig(provider, baseUrl.trim(), model.trim(), key.trim())) }, enabled = key.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()) {
                Text(Strings.SAVE)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}
