// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime

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

/**
 * Full-schema database with demo data: accounts, operation types, budget items, and operations.
 * Used by the `--self-test` smoke check and integration tests that need a populated DB.
 */
fun createDemoFixture(file: File, operationCount: Int = 12, softDeleteEvery: Int = 4): File {
    createXCashSchemaFixture(file)
    Jackcess.open(file).use { db ->
        val accounts = db.getTable("Account")
        for ((i, name) in listOf("Кошелёк", "Карта", "Вклад", "Заначка", "Копилка").withIndex()) {
            accounts.addRow(Jackcess.AUTO_NUMBER, null, null, null, null, false, name)
        }
        val types = db.getTable("TypeOper")
        for ((i, name) in listOf("Зарплата", "Продукты", "Транспорт").withIndex()) {
            types.addRow(Jackcess.AUTO_NUMBER, null, null, null, null, false, name, null, (if (i == 0) 1L else -1L))
        }
        val ops = db.getTable("DvizhenieDeneg")
        val now = LocalDateTime.now()
        for (i in 1..operationCount) {
            ops.addRow(
                Jackcess.AUTO_NUMBER,
                null, null, null, null,
                i % softDeleteEvery == 0,
                now.minusDays(i.toLong()),
                BigDecimal.valueOf(i * 10L),
                null, null, null,
                "fixture operation $i",
                null, null, null,
            )
        }
    }
    return file
}
