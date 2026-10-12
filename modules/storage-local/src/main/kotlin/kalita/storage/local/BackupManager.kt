// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps the last [keep] copies of a database before each write (NFR-4, dev_plan.md §9.4).
 * [backup] never deletes anything: callers [prune] only once the write the backup guards has
 * succeeded, so failed writes can't push good backups out.
 */
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
        return target
    }

    /** Oldest first: by timestamp, then by the "-N" suffix given to copies made within the same second. */
    fun list(databaseName: String): List<File> =
        directory.listFiles { f -> f.name.startsWith("$databaseName-") && f.extension == "mdb" }
            .orEmpty()
            .sortedWith(compareBy({ it.nameWithoutExtension.removeSuffix(sameSecondSuffix(it)) }, { sameSecondSuffix(it).drop(1).toIntOrNull() ?: 0 }))

    private fun sameSecondSuffix(f: File): String =
        SAME_SECOND.find(f.nameWithoutExtension)?.groupValues?.get(1).orEmpty()

    fun prune(databaseName: String) {
        list(databaseName).dropLast(keep).forEach { it.delete() }
    }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val SAME_SECOND = Regex("""-\d{8}-\d{6}(-\d+)$""")
    }
}
