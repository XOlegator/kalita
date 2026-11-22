// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.agent.api.ReceiptImage
import java.io.File
import java.nio.file.Files
import java.util.UUID

internal class ReceiptFrameStore(dataDirectory: File) {
    private val root = File(dataDirectory, "android-receipt-frames")
    private val proposals = File(dataDirectory, "receipt-proposals")

    fun <T> publishing(id: String, block: () -> T): T {
        val key = File(root, id).absoluteFile.normalize().path
        synchronized(lock) { active[key] = (active[key] ?: 0) + 1 }
        try {
            return block()
        } finally {
            synchronized(lock) {
                val count = active.getValue(key)
                if (count == 1) active.remove(key) else active[key] = count - 1
            }
        }
    }

    fun deleteOrphans() = synchronized(lock) {
        val proposalNames = if (Files.notExists(proposals.toPath())) emptySet() else {
            proposals.listFiles()?.map { it.name }?.toSet() ?: return@synchronized
        }
        root.listFiles()?.forEach { directory ->
            if (validId(directory.name) && directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
                directory.canonicalFile.parentFile == root.canonicalFile &&
                directory.absoluteFile.normalize().path !in active && "${directory.name}.json" !in proposalNames
            ) runCatching { directory.deleteRecursively() }
        }
    }

    fun save(id: String, images: List<ReceiptImage>) {
        require(validId(id))
        require(images.size <= 10) { "Слишком много снимков чека" }
        if (images.isEmpty()) return
        check(root.mkdirs() || root.isDirectory) { "Не удалось создать хранилище снимков" }
        val directory = File(root, id)
        Files.createDirectory(directory.toPath())
        try {
            images.forEachIndexed { index, image -> File(directory, "${index + 1}.jpg").writeBytes(image.bytes) }
        } catch (e: Throwable) {
            directory.deleteRecursively()
            throw e
        }
    }

    fun delete(id: String) {
        require(validId(id))
        File(root, id).deleteRecursively()
    }

    fun deleteFrames(id: String) {
        require(validId(id))
        val directory = File(root, id)
        require(directory.canonicalFile.parentFile == root.canonicalFile) { "Недопустимый каталог снимков" }
        check(directory.deleteRecursively()) { "Не удалось удалить снимки чека" }
    }

    fun frames(id: String): List<File> {
        if (!validId(id)) return emptyList()
        val directory = File(root, id)
        if (directory.canonicalFile.parentFile != root.canonicalFile) return emptyList()
        return (1..10).map { File(directory, "$it.jpg") }
            .filter { it.isFile && it.canonicalFile.parentFile == directory.canonicalFile }
    }

    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private companion object {
        val lock = Any()
        val active = mutableMapOf<String, Int>()
    }
}