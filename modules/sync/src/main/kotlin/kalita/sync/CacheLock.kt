// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Guards one Nextcloud database's local cache directory against two processes on the *same*
 * device racing `AtomicCommit` against the same cache file — the cross-device case is what the
 * whole ETag/`If-Match` protocol already exists for (dev_plan.md §10), but nothing stops two
 * Kalita instances on one machine from corrupting each other's view of it. Deliberately not
 * `storage-local`'s `DatabaseLock`: that class's extra job — detecting the original X-Cash's own
 * `.ldb` lock file — makes no sense here (X-Cash never opens this cache), and reusing it would
 * pull `sync` into depending on `storage-local`, which §9.3 reserves for local-mode-only code.
 */
class CacheLock private constructor(private val raf: RandomAccessFile, private val channel: FileChannel, private val lock: FileLock) :
    AutoCloseable {
    override fun close() {
        lock.release()
        channel.close()
        raf.close()
    }

    companion object {
        /** `null` if another process already holds the lock — never blocks waiting for it. */
        fun tryAcquire(dataDirectory: File): CacheLock? {
            dataDirectory.mkdirs()
            val raf = RandomAccessFile(File(dataDirectory, "cache.lock"), "rw")
            val channel = raf.channel
            val lock = try {
                channel.tryLock()
            } catch (e: Exception) {
                channel.close()
                raf.close()
                throw e
            }
            if (lock == null) {
                channel.close()
                raf.close()
                return null
            }
            return CacheLock(raf, channel, lock)
        }
    }
}
