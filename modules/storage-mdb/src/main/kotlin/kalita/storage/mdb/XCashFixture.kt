// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import java.io.File

/**
 * Empty database with the exact MVP table layout of dev_plan.md appendix A (generated from
 * [XCASH_SCHEMA], no vendor data). Used by tests and as the base for stage 1 fixtures.
 */
fun createXCashSchemaFixture(file: File): File {
    Jackcess.create(file).use { db ->
        for (spec in XCASH_SCHEMA) {
            val builder = TableBuilder(spec.table.tableName)
            for ((name, type) in spec.columns) {
                val column = ColumnBuilder(name, type)
                if (name == "id") Jackcess.autoNumber(column)
                builder.addColumn(column)
            }
            builder.addIndex(Jackcess.primaryKey(Jackcess.indexOn(IndexBuilder(Jackcess.PRIMARY_KEY_NAME), true, "id")))
            for ((column, ascending) in spec.secondaryIndexes) {
                builder.addIndex(Jackcess.indexOn(IndexBuilder("idx$column"), ascending, column))
            }
            builder.toTable(db)
        }
    }
    return file
}
