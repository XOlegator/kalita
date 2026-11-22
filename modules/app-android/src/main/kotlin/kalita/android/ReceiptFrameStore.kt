// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.agent.api.ReceiptImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class ReceiptFrameStore(dataDirectory: File) {
    private val root = File(dataDirectory, "android-receipt-frames")

    fun save(id: String, images: List<ReceiptImage>) {
        require(validId(id))
        require(images.size <= 10) { "Слишком много снимков чека" }
        if (images.isEmpty()) return
        check(root.mkdirs() || root.isDirectory) { "Не удалось создать хранилище снимков" }
        val temporary = Files.createTempDirectory(root.toPath(), "frames-").toFile()
        try {
            images.forEachIndexed { index, image -> File(temporary, "${index + 1}.jpg").writeBytes(image.bytes) }
            Files.move(temporary.toPath(), File(root, id).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.deleteRecursively()
        }
    }

    fun frames(id: String): List<File> {
        if (!validId(id)) return emptyList()
        val directory = File(root, id)
        if (directory.canonicalFile.parentFile != root.canonicalFile) return emptyList()
        return (1..10).map { File(directory, "$it.jpg") }
            .filter { it.isFile && it.canonicalFile.parentFile == directory.canonicalFile }
    }

    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}