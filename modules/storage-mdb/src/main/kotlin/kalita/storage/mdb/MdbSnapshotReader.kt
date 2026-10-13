// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.github.spannm.jackcess.Database
import io.github.spannm.jackcess.DatabaseBuilder
import kalita.storage.api.DbSnapshot
import kalita.storage.api.Row
import kalita.storage.api.Table
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Reads the application tables of a `Data.mdb` into a [DbSnapshot]; never writes. */
object MdbSnapshotReader {
    fun read(file: File): DbSnapshot =
        DatabaseBuilder(file).withReadOnly(true).open().use { read(it) }

    internal fun read(db: Database): DbSnapshot {
        val check = SchemaFingerprint.check(db)
        require(check is SchemaCheck.Compatible) { "Schema is not readable: $check" }
        return DbSnapshot(
            XCASH_SCHEMA.associate { spec ->
                val columns = spec.columns.map { it.first }
                spec.table to db.getTable(spec.table.tableName).map { raw -> readRow(raw, columns) }
            },
        )
    }

    private fun readRow(raw: Map<String, Any?>, columns: List<String>): Row =
        columns.associateWithTo(LinkedHashMap()) { normalize(raw[it]) }

    // Same canonical types as MdbEngine writes: Long ids/references, seconds-precision dates.
    private fun normalize(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is LocalDateTime -> value.truncatedTo(ChronoUnit.SECONDS)
        else -> value
    }
}
