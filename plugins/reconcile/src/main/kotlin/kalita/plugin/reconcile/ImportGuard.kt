// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Fail closed if a previous import may have committed without recording its links. */
class ImportGuard(dataDirectory: File) {
    private val directory = dataDirectory
    private val marker = File(directory, "bank-import-in-progress")

    /** Claims the import across processes. An existing marker requires manual investigation. */
    fun begin(): Boolean {
        Files.createDirectories(directory.toPath())
        return try {
            Files.newByteChannel(marker.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { }
            true
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            false
        }
    }

    /** Call only once links have been recorded, or the commit is known not to have happened. */
    fun finish() {
        Files.delete(marker.toPath())
    }
}