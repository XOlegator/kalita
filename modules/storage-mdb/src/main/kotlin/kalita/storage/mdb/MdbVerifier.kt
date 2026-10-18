// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import kalita.storage.api.Table
import java.io.File

/** Outcome of [MdbVerifier.verify]: [errors] make the file unsafe to write, [warnings] are data oddities. */
data class VerifyReport(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/** Read-only integrity check of a `Data.mdb`: schema, index consistency, unique ids and dangling references. */
object MdbVerifier {
    // (table, column) -> referenced table; logical links the database itself does not declare (dev_plan.md §4.2).
    private val references: List<Triple<Table, String, Table>> = listOf(
        Triple(Table.Operations, "Currency_ID", Table.Currencies),
        Triple(Table.Operations, "GroupBudget_ID", Table.BudgetItems),
        Triple(Table.Operations, "TypeOper_ID", Table.OperationTypes),
        Triple(Table.Operations, "People_ID", Table.People),
        Triple(Table.Operations, "AccountFrom", Table.Accounts),
        Triple(Table.Operations, "AccountTo", Table.Accounts),
        Triple(Table.BudgetItems, "Parent_ID", Table.BudgetItems),
        Triple(Table.OperationTypes, "GroupBudget_ID", Table.BudgetItems),
        Triple(Table.Rates, "Currency_ID", Table.Currencies),
    )

    fun verify(file: File): VerifyReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        Jackcess.open(file, readOnly = true).use { db ->
            (SchemaFingerprint.check(db) as? SchemaCheck.Mismatch)?.let { m ->
                errors += m.problems.map { "schema: $it" }
                return VerifyReport(errors, warnings)
            }
            for (table in Table.entries) {
                val t = db.getTable(table.tableName)
                for (index in t.indexes) {
                    val walked = Jackcess.cursor(index).count()
                    if (walked != t.rowCount) errors += "${table.tableName}: index ${index.name} lists $walked of ${t.rowCount} rows"
                }
            }
            val snapshot = MdbSnapshotReader.read(db)
            for (table in Table.entries) {
                val ids = snapshot.rows(table).map { (it["id"] as Number).toLong() }
                ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                    .forEach { errors += "${table.tableName}: duplicate id $it" }
            }
            for ((table, column, target) in references) {
                for (row in snapshot.rows(table)) {
                    val ref = (row[column] as? Number)?.toLong() ?: continue
                    if (ref != 0L && snapshot.row(target, ref) == null) {
                        warnings += "${table.tableName}#${row["id"]}.$column -> missing ${target.tableName}#$ref"
                    }
                }
            }
        }
        return VerifyReport(errors, warnings)
    }
}
