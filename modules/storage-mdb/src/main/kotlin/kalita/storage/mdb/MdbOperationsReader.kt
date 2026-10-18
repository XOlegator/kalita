// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import java.io.File

internal const val OPERATIONS_TABLE = "DvizhenieDeneg"
internal const val DELETED_COLUMN = "del"

// Soft-deleted rows (del = true) are excluded, matching how X-Cash itself counts operations
// (dev_plan.md §8.2 research notes) — Kalita must not resurrect them in a plain count.
fun countOperations(file: File): Int =
    Jackcess.open(file).use { db ->
        db.getTable(OPERATIONS_TABLE).count { row -> row.get(DELETED_COLUMN) != true }
    }
