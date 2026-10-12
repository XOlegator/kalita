// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.github.spannm.jackcess.ColumnBuilder
import io.github.spannm.jackcess.Database.FileFormat
import io.github.spannm.jackcess.DatabaseBuilder
import io.github.spannm.jackcess.IndexBuilder
import io.github.spannm.jackcess.TableBuilder
import java.io.File

/**
 * Empty database with the exact MVP table layout of dev_plan.md appendix A (generated from
 * [XCASH_SCHEMA], no vendor data). Used by tests and as the base for stage 1 fixtures.
 */
fun createXCashSchemaFixture(file: File): File {
    DatabaseBuilder.create(FileFormat.V2000, file).use { db ->
        for (spec in XCASH_SCHEMA) {
            val builder = TableBuilder(spec.table.tableName)
            for ((name, type) in spec.columns) {
                val column = ColumnBuilder(name, type)
                if (name == "id") column.withAutoNumber(true)
                builder.addColumn(column)
            }
            builder.addIndex(IndexBuilder(IndexBuilder.PRIMARY_KEY_NAME).withColumns("id").withPrimaryKey())
            for ((column, ascending) in spec.secondaryIndexes) {
                builder.addIndex(IndexBuilder("idx$column").withColumns(ascending, column))
            }
            builder.toTable(db)
        }
    }
    return file
}
