// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

/** Hex encoding for a `QHeaderView.saveState()` blob, so it fits in a [SavedView]'s text field. */
internal fun ByteArray.toHeaderHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("%02x".format(b))
    return sb.toString()
}

internal fun String.fromHeaderHex(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length, was $length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
