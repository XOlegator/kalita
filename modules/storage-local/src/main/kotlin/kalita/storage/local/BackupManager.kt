// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Keeps the last [keep] copies of a database before each write (NFR-4, dev_plan.md §9.4). */
class BackupManager(private val directory: File, private val keep: Int = 10) {
    init {
        require(keep >= 1) { "keep must be positive" }
    }

    fun backup(database: File, now: LocalDateTime = LocalDateTime.now()): File {
        directory.mkdirs()
        val stamp = now.format(FORMAT)
        var target = File(directory, "${database.nameWithoutExtension}-$stamp.mdb")
        var n = 1
        while (target.exists()) target = File(directory, "${database.nameWithoutExtension}-$stamp-${n++}.mdb")
        Files.copy(database.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        prune(database.nameWithoutExtension)
        return target
    }

    fun list(databaseName: String): List<File> =
        directory.listFiles { f -> f.name.startsWith("$databaseName-") && f.extension == "mdb" }
            .orEmpty()
            .sortedBy { it.name }

    private fun prune(databaseName: String) {
        list(databaseName).dropLast(keep).forEach { it.delete() }
    }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
