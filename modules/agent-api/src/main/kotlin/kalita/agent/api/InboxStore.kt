// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.core.OperationDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val INBOX_STATUS_PENDING = "pending"
internal const val INBOX_STATUS_APPROVED = "approved"
internal const val INBOX_STATUS_REJECTED = "rejected"
internal const val INBOX_STATUS_APPLYING = "applying"

internal data class InboxEntry(
    val id: String,
    val createdAt: Instant,
    val device: String,
    val clientRequestId: String,
    val draft: OperationDraft,
    val status: String,
)

/**
 * Minimal «Входящие» (FR-AG4): `<dataDirectory>/inbox.jsonl` holds the *current* state of every
 * draft, one line per entry, rewritten whole on every status change (not appended) — simplest
 * correct approach for a file this small, one row per agent-proposed operation awaiting a human.
 */
internal class InboxStore(dataDirectory: File) {
    private val directory = dataDirectory.canonicalFile
    private val file = File(directory, "inbox.jsonl")

    fun <T> locked(block: LockedInbox.() -> T): T {
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "Cannot create inbox directory: $directory" }
        return synchronized(locks.computeIfAbsent(directory.path) { Any() }) {
            FileChannel.open(File(directory, "inbox.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { LockedInbox().block() }
            }
        }
    }

    fun readAll(): List<InboxEntry> = locked { readAll() }

    fun add(draft: OperationDraft, device: String, clientRequestId: String): InboxEntry = locked { add(draft, device, clientRequestId) }

    inner class LockedInbox {
        fun readAll(): List<InboxEntry> {
            if (!file.isFile) return emptyList()
            return file.readLines()
                .filter { it.isNotBlank() }
                .map { decode(Json.parseToJsonElement(it).jsonObject) }
        }

        fun find(id: String): InboxEntry? = readAll().firstOrNull { it.id == id }

        fun add(draft: OperationDraft, device: String, clientRequestId: String): InboxEntry {
            val entry = InboxEntry(UUID.randomUUID().toString(), Instant.now(), device, clientRequestId, draft, INBOX_STATUS_PENDING)
            writeAll(readAll() + entry)
            return entry
        }

        fun updateStatus(id: String, status: String) {
            writeAll(readAll().map { if (it.id == id) it.copy(status = status) else it })
        }

        private fun writeAll(entries: List<InboxEntry>) {
            val text = entries.joinToString("") { encode(it).toString() + "\n" }
            val temp = Files.createTempFile(directory.toPath(), "inbox-", ".tmp")
            try {
                FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                    val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                // Directory fsync is unavailable on some platforms, notably Windows.
                try {
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                } catch (_: java.io.IOException) {
                    // Atomic replacement still protects against process crashes.
                } catch (_: UnsupportedOperationException) {
                    // The provider does not support syncing directories.
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
    }

    private fun encode(e: InboxEntry): JsonObject = buildJsonObject {
        put("id", e.id)
        put("createdAt", e.createdAt.toString())
        put("device", e.device)
        put("clientRequestId", e.clientRequestId)
        put("draft", OperationDraftJson.encode(e.draft))
        put("status", e.status)
    }

    private fun decode(o: JsonObject): InboxEntry = InboxEntry(
        id = o.getValue("id").jsonPrimitive.content,
        createdAt = Instant.parse(o.getValue("createdAt").jsonPrimitive.content),
        device = o.getValue("device").jsonPrimitive.content,
        clientRequestId = o.getValue("clientRequestId").jsonPrimitive.content,
        draft = OperationDraftJson.decode(o.getValue("draft").jsonObject),
        status = o.getValue("status").jsonPrimitive.content,
    )
}
