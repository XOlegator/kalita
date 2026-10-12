// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.ConcurrentHashMap

/** How a database may be used, decided when it is opened (FR-4). */
sealed interface OpenMode {
    data object ReadWrite : OpenMode
    data class ReadOnly(val reason: String) : OpenMode
}

/**
 * Guards against simultaneous work with the original X-Cash or another Kalita instance (FR-4):
 * an own `<name>.kalita.lock` file held with an OS lock, and a check for Jet's `<name>.ldb`
 * (which Jet removes on a clean close). Anything else holding the file makes us read-only.
 *
 * X-Cash knows nothing about our lock file, so it can still open the database later; writers
 * must call [jetLockFile] again right before each write.
 */
class DatabaseLock private constructor(
    val mode: OpenMode,
    private val channel: FileChannel?,
    private val lock: FileLock?,
    private val lockPath: String?,
) : AutoCloseable {

    private var closed = false

    @Synchronized
    override fun close() {
        // A repeated close must not drop the registry entry of a later owner of the same file.
        if (closed) return
        closed = true
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lockPath?.let { heldInThisProcess.remove(it) }
        // Left in place on purpose: deleting would race a second instance that already opened it.
    }

    companion object {
        // OS file locks are per process: a second channel on the same file in this JVM would not be
        // refused by the OS, and closing it may drop the first one's lock (POSIX). So same-process
        // contention is settled here, and only one channel per lock file is ever opened.
        private val heldInThisProcess = ConcurrentHashMap.newKeySet<String>()

        fun acquire(database: File): DatabaseLock {
            jetLockFile(database)?.let { return readOnly("the original X-Cash appears to have the database open (${it.name} exists)") }

            val lockFile = File(database.absoluteFile.parentFile, database.nameWithoutExtension + ".kalita.lock")
            val path = try {
                lockFile.canonicalPath
            } catch (e: IOException) {
                return readOnly("cannot resolve lock file: ${e.message}")
            }
            if (!heldInThisProcess.add(path)) return readOnly("the database is already open in this Kalita")

            var channel: FileChannel? = null
            try {
                channel = RandomAccessFile(lockFile, "rw").channel
                val lock = channel.tryLock()
                if (lock != null) return DatabaseLock(OpenMode.ReadWrite, channel, lock, path)
                channel.close()
                heldInThisProcess.remove(path)
                return readOnly("another Kalita instance has the database open")
            } catch (e: IOException) {
                runCatching { channel?.close() }
                heldInThisProcess.remove(path)
                return readOnly("cannot lock the database: ${e.message}")
            }
        }

        /** Jet's own lock file next to [database], present while X-Cash (or Access) has it open. */
        fun jetLockFile(database: File): File? =
            listOf("ldb", "laccdb")
                .map { File(database.absoluteFile.parentFile, database.nameWithoutExtension + "." + it) }
                .firstOrNull { it.exists() }

        private fun readOnly(reason: String) = DatabaseLock(OpenMode.ReadOnly(reason), null, null, null)
    }
}
