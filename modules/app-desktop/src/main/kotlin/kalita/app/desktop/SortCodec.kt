// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.core.JournalColumn
import kalita.core.SortKey

/**
 * A compact text form of the journal's sort, independent of kotlinx.serialization (`app-desktop`
 * has no dependency on it — only `core` does) — used for both [SavedView] and the per-screen
 * `QSettings` state in [JournalGrid] (dev_plan.md FR-42/FR-43).
 */
internal fun encodeSortCompact(sort: List<SortKey>): String =
    sort.joinToString(";") { "${it.column.name}:${it.descending}" }

internal fun decodeSortCompact(text: String): List<SortKey> =
    text.split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size != 2) return@mapNotNull null
        val column = runCatching { JournalColumn.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
        SortKey(column, parts[1].toBoolean())
    }
