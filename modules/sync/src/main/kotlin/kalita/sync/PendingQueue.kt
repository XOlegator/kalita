// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import kalita.storage.api.ChangeSet
import kalita.storage.api.ChangeSetJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Change sets applied locally (§10.2's optimistic local apply) but not yet confirmed on the
 * server, in the order they were submitted — "очередь" (dev_plan.md §10.2/§10.7). A whole-file
 * JSON rewrite on every change, not `ChangeHistory`'s append-only JSONL: entries here also have to
 * be *removed* once the server confirms them, which append-only has no way to do without its own
 * compaction step — the same tradeoff `AgentConnectionStore`/`InboxStore` already accepted for
 * small, rewrite-on-every-change files elsewhere in this project. Queues are a handful of entries
 * at most under normal use, so reading/writing the whole file each time costs nothing noticeable.
 */
class PendingQueue(private val file: File) {
    @Synchronized
    fun read(): List<ChangeSet> {
        if (!file.isFile) return emptyList()
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return emptyList()
        return Json.parseToJsonElement(text).jsonArray.map { ChangeSetJson.decode(it.jsonObject) }
    }

    @Synchronized
    fun append(changeSet: ChangeSet) = write(read() + changeSet)

    /** Drops the first [count] entries — the ones a successful `PUT` just confirmed. */
    @Synchronized
    fun removeFirst(count: Int) {
        if (count <= 0) return
        write(read().drop(count))
    }

    private fun write(all: List<ChangeSet>) {
        file.parentFile?.mkdirs()
        val json = buildJsonArray { all.forEach { add(ChangeSetJson.encode(it)) } }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
