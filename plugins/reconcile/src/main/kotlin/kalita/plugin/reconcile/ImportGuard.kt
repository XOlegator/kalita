// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kalita.storage.api.TempKey
import kotlinx.serialization.json.*

/** Fail closed if a previous import may have committed without recording its links. */
class ImportGuard(dataDirectory: File) : AutoCloseable {
    private val directory = dataDirectory
    private val marker = File(directory, "bank-import-in-progress")
    private val lockFile = File(directory, "bank-import.lock")
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var lockPath: java.nio.file.Path? = null

    private companion object {
        val heldPaths = HashSet<java.nio.file.Path>()
    }

    enum class Recovery { BUSY, NO_MARKER, UNCONFIRMED, RECOVERED }

    private fun acquire(): Boolean {
        check(lock == null) { "Import already held by this guard" }
        Files.createDirectories(directory.toPath())
        val path = directory.toPath().toRealPath().resolve(lockFile.name)
        synchronized(heldPaths) {
            if (!heldPaths.add(path)) return false
            try {
                val opened = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val acquired = try {
                    opened.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } catch (e: Throwable) {
                    opened.close()
                    throw e
                }
                if (acquired == null) {
                    opened.close()
                    heldPaths.remove(path)
                    return false
                }
                channel = opened
                lock = acquired
                lockPath = path
                return true
            } catch (e: Throwable) {
                heldPaths.remove(path)
                throw e
            }
        }
    }

    /** Legacy claim. Its empty marker cannot be recovered automatically. */
    @Synchronized
    fun begin(): Boolean {
        if (!acquire()) return false
        return try {
            Files.newByteChannel(marker.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { }
            true
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            close()
            false
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** Persist before submit; null ID is allowed only for a link-only preparation. */
    @Synchronized
    fun begin(prepared: ReconcileWriter.Prepared, changeSetId: UUID?, at: Instant, device: String): Boolean {
        require((changeSetId == null) == prepared.isEmpty) { "A change set ID is required exactly when there are changes" }
        val text = encode(prepared, changeSetId, at, device)
        if (!acquire()) return false
        try {
            if (!Files.notExists(marker.toPath())) {
                close()
                return false
            }
            val tmp = Files.createTempFile(directory.toPath(), "bank-import-", ".tmp")
            try {
                FileChannel.open(tmp, StandardOpenOption.WRITE).use {
                    val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) it.write(bytes)
                    it.force(true)
                }
                Files.move(tmp, marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            return true
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /**
     * The callback must return the temp keys from the history entry for the exact ID, or null
     * if it cannot prove that ID was applied. Exceptions leave the marker intact.
     */
    @Synchronized
    fun recover(confirmApplied: (UUID) -> Map<TempKey, Long>?): Recovery {
        if (!acquire()) return Recovery.BUSY
        try {
            if (Files.notExists(marker.toPath())) return Recovery.NO_MARKER
            val intent = decode(marker.readText())
            val keys = if (intent.id == null) emptyMap() else confirmApplied(intent.id) ?: return Recovery.UNCONFIRMED
            val links = intent.prepared.links(keys, intent.at, intent.device)
            val store = ReconLinkStore(directory)
            val existing = store.current()
            val missing = links.filter { link ->
                val old = existing[link.bankTx]
                check(old == null || old == link) { "Conflicting link for ${link.bankTx}" }
                old == null
            }
            store.append(missing)
            Files.delete(marker.toPath())
            return Recovery.RECOVERED
        } finally {
            close()
        }
    }

    /** Call only once links have been recorded, or the commit is known not to have happened. */
    @Synchronized
    fun finish() {
        check(lock != null) { "Import lock not held" }
        try {
            Files.delete(marker.toPath())
        } finally {
            close()
        }
    }

    /** Release the live lock without clearing an uncertain marker. */
    @Synchronized
    override fun close() {
        synchronized(heldPaths) {
            try {
                lock?.release()
            } finally {
                lock = null
                try {
                    channel?.close()
                } finally {
                    channel = null
                    heldPaths.remove(lockPath)
                    lockPath = null
                }
            }
        }
    }

    private data class Intent(val id: UUID?, val prepared: ReconcileWriter.Prepared, val at: Instant, val device: String)

    private fun encode(prepared: ReconcileWriter.Prepared, id: UUID?, at: Instant, device: String): String {
        validate(prepared, id)
        return buildJsonObject {
            put("version", 1)
            put("changeSetId", id?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("at", at.toString())
            put("device", device)
            put("insertCount", prepared.insertCount)
            put("updateCount", prepared.updateCount)
            put("fixed", JsonArray(prepared.fixed.map { link ->
                buildJsonObject {
                    put("bankTx", link.bankTx)
                    put("rows", JsonArray(link.rows.map(::JsonPrimitive)))
                    put("kind", link.kind.name)
                }
            }))
            put("pending", JsonArray(prepared.pending.map { (bankTx, key) ->
                buildJsonObject { put("bankTx", bankTx); put("tempKey", key.value) }
            }))
        }.toString()
    }

    private fun decode(text: String): Intent {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        val idValue = root.getValue("changeSetId")
        val id = if (idValue == JsonNull) null else UUID.fromString(idValue.jsonPrimitive.content)
        val at = Instant.parse(root.getValue("at").jsonPrimitive.content)
        val device = root.getValue("device").jsonPrimitive.content
        val fixed = root.getValue("fixed").jsonArray.map {
            val obj = it.jsonObject
            ReconLink(obj.getValue("bankTx").jsonPrimitive.content, obj.getValue("rows").jsonArray.map { row -> row.jsonPrimitive.long }, LinkKind.valueOf(obj.getValue("kind").jsonPrimitive.content), at, device)
        }
        val pending = root.getValue("pending").jsonArray.map {
            val obj = it.jsonObject
            obj.getValue("bankTx").jsonPrimitive.content to TempKey(obj.getValue("tempKey").jsonPrimitive.content)
        }
        val prepared = ReconcileWriter.Prepared(fixed, pending, root.getValue("insertCount").jsonPrimitive.int, root.getValue("updateCount").jsonPrimitive.int)
        validate(prepared, id)
        return Intent(id, prepared, at, device)
    }

    private fun validate(prepared: ReconcileWriter.Prepared, id: UUID?) {
        require(prepared.insertCount == prepared.pending.size && prepared.updateCount >= 0)
        require((id == null) == prepared.isEmpty)
        val bankTxs = prepared.fixed.map { it.bankTx } + prepared.pending.map { it.first }
        require(bankTxs.all { it.isNotBlank() } && bankTxs.distinct().size == bankTxs.size)
        val keys = prepared.pending.map { it.second.value }
        require(keys.all { it.isNotBlank() } && keys.distinct().size == keys.size)
    }
}