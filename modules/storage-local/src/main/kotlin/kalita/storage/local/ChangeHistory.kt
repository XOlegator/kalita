// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import kalita.storage.api.ChangeSet
import kalita.storage.api.TempKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant

/** A change set as applied: [tempKeys] holds the real ids given to the rows it inserted. */
data class HistoryEntry(val changeSet: ChangeSet, val appliedAt: Instant, val tempKeys: Map<TempKey, Long>)

/**
 * Append-only local history of applied change sets (FR-7, dev_plan.md §9.4): one JSON object per
 * line, flushed to disk per entry. A half-written line from a crash is skipped on read.
 *
 * [read] loads the whole file; fine for years of manual entry, but the history viewer (stage 5)
 * should page through it rather than hold it all.
 */
class ChangeHistory(private val file: File) {
    fun append(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        file.parentFile?.mkdirs()
        val text = entries.joinToString("") { Json.encodeToString(JsonObject.serializer(), encode(it)) + "\n" }
        // After a crash mid-write the file may end without a newline; start on a fresh line so the
        // new entry isn't glued to the torn one and lost with it.
        val prefix = if (endsTorn()) "\n" else ""
        FileOutputStream(file, true).use { out ->
            out.write((prefix + text).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    fun read(): List<HistoryEntry> {
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) null else runCatching { decode(Json.parseToJsonElement(line).jsonObject) }.getOrNull()
        }
    }

    private fun endsTorn(): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
    }

    /** Throws if [changeSet] holds a value the history can't store — call before committing it. */
    fun checkStorable(changeSet: ChangeSet) {
        ChangeSetJson.encode(changeSet)
    }

    private fun encode(e: HistoryEntry) = buildJsonObject {
        put("appliedAt", kotlinx.serialization.json.JsonPrimitive(e.appliedAt.toString()))
        put("changeSet", ChangeSetJson.encode(e.changeSet))
        put("tempKeys", JsonObject(e.tempKeys.entries.associate { (k, v) -> k.value to kotlinx.serialization.json.JsonPrimitive(v) }))
    }

    private fun decode(o: JsonObject) = HistoryEntry(
        changeSet = ChangeSetJson.decode(o.getValue("changeSet").jsonObject),
        appliedAt = Instant.parse(o.getValue("appliedAt").jsonPrimitive.content),
        tempKeys = o.getValue("tempKeys").jsonObject.entries.associate { (k, v) -> TempKey(k) to v.jsonPrimitive.long },
    )
}
