// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** How a database may be used, decided when it is opened (FR-4). */
sealed interface OpenMode {
    data object ReadWrite : OpenMode
    data class ReadOnly(val reason: String) : OpenMode
}

/**
 * Guards against simultaneous work with the original X-Cash or another Kalita instance (FR-4):
 * an own `<name>.kalita.lock` file held with an OS lock, and a check for Jet's `<name>.ldb`
 * (which Jet removes on a clean close). Anything else holding the file makes us read-only.
 */
class DatabaseLock private constructor(
    val mode: OpenMode,
    private val channel: FileChannel?,
    private val lock: FileLock?,
    private val lockFile: File?,
) : AutoCloseable {

    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        // Left in place on purpose: deleting would race a second instance that already opened it.
    }

    companion object {
        fun acquire(database: File): DatabaseLock {
            jetLockFile(database)?.let { return readOnly("the original X-Cash appears to have the database open (${it.name} exists)") }

            val lockFile = File(database.parentFile, database.nameWithoutExtension + ".kalita.lock")
            return try {
                val channel = RandomAccessFile(lockFile, "rw").channel
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    readOnly("another Kalita instance has the database open")
                } else {
                    DatabaseLock(OpenMode.ReadWrite, channel, lock, lockFile)
                }
            } catch (e: IOException) {
                readOnly("cannot create lock file: ${e.message}")
            }
        }

        private fun jetLockFile(database: File): File? =
            listOf("ldb", "laccdb")
                .map { File(database.parentFile, database.nameWithoutExtension + "." + it) }
                .firstOrNull { it.exists() }

        private fun readOnly(reason: String) = DatabaseLock(OpenMode.ReadOnly(reason), null, null, null)
    }
}
