// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import java.io.File
import java.security.MessageDigest

/** Size, modification time and content hash — how external edits are detected (FR-8, dev_plan.md §9.5). */
data class FileFingerprint(val size: Long, val modifiedMillis: Long, val sha256: String) {
    companion object {
        fun of(file: File): FileFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return FileFingerprint(file.length(), file.lastModified(), digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
