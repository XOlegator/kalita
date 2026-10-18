// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import java.io.File

/**
 * The Jackcess line this build does *not* compile against — here upstream, read straight through its
 * own API (twin: src/upstreamTest/). [CrossLineTest] uses it to prove a file written by one line
 * reads back identically through the other, which is what makes switching lines safe (§8.3).
 */
internal object OtherLine {
    const val NAME = "upstream"

    fun dump(file: File): JetDump =
        com.healthmarketscience.jackcess.DatabaseBuilder(file).setReadOnly(true).open().use { db ->
            db.dateTimeType = com.healthmarketscience.jackcess.DateTimeType.LOCAL_DATE_TIME
            db.tableNames.sorted().associateWith { name ->
                val table = db.getTable(name)
                TableDump(
                    rows = table.map { LinkedHashMap(it) },
                    indexWalks = table.indexes.associate {
                        it.name to com.healthmarketscience.jackcess.CursorBuilder.createCursor(it).count()
                    },
                )
            }
        }
}
