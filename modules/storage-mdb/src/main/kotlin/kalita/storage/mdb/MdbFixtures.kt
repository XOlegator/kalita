// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.github.spannm.jackcess.Column
import io.github.spannm.jackcess.ColumnBuilder
import io.github.spannm.jackcess.DataType
import io.github.spannm.jackcess.Database.FileFormat
import io.github.spannm.jackcess.DatabaseBuilder
import io.github.spannm.jackcess.TableBuilder
import java.io.File
import java.time.LocalDateTime

// Synthetic Data.mdb-shaped fixture for demos and tests: no vendor data ships in this repo
// (dev_plan.md §13 — copyright on the original's content), so fixtures are generated on the fly
// instead of checked in as binary files.
fun createOperationsFixture(file: File, operationCount: Int, softDeleteEvery: Int = 4): File {
    DatabaseBuilder.create(FileFormat.V2000, file).use { db ->
        val table = TableBuilder(OPERATIONS_TABLE)
            .addColumn(ColumnBuilder("id", DataType.LONG).withAutoNumber(true))
            .addColumn(ColumnBuilder("Date", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder("Summa", DataType.DOUBLE))
            .addColumn(ColumnBuilder("Comment", DataType.TEXT))
            .addColumn(ColumnBuilder(DELETED_COLUMN, DataType.BOOLEAN))
            .toTable(db)

        for (i in 1..operationCount) {
            table.addRow(
                Column.AUTO_NUMBER,
                LocalDateTime.now().minusDays(i.toLong()),
                i * 10.0,
                "fixture operation $i",
                i % softDeleteEvery == 0,
            )
        }
    }
    return file
}
