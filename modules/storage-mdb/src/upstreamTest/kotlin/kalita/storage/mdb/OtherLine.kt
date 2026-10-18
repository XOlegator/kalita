// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import java.io.File

/**
 * The Jackcess line this build does *not* compile against — here the spannm fork, read straight through its
 * own API (twin: src/spannmTest/). [CrossLineTest] uses it to prove a file written by one line
 * reads back identically through the other, which is what makes switching lines safe (§8.3).
 */
internal object OtherLine {
    const val NAME = "spannm"

    fun dump(file: File): JetDump =
        io.github.spannm.jackcess.DatabaseBuilder(file).withReadOnly(true).open().use { db ->
            db.dateTimeType = io.github.spannm.jackcess.DateTimeType.LOCAL_DATE_TIME
            db.tableNames.sorted().associateWith { name ->
                val table = db.getTable(name)
                TableDump(
                    rows = table.map { LinkedHashMap(it) },
                    indexWalks = table.indexes.associate {
                        it.name to io.github.spannm.jackcess.CursorBuilder.createCursor(it).count()
                    },
                )
            }
        }
}
