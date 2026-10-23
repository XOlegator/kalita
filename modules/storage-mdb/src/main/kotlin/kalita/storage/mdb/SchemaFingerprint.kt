// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb


/** Result of checking a database against the layout Kalita knows how to write (dev_plan.md §9.5). */
sealed interface SchemaCheck {
    /** All MVP tables match appendix A — writing is allowed. */
    data object Compatible : SchemaCheck

    /** Unknown or modified schema — the database may only be read. */
    data class Mismatch(val problems: List<String>) : SchemaCheck
}

object SchemaFingerprint {
    internal fun check(db: Database): SchemaCheck {
        val problems = buildList {
            for (spec in XCASH_SCHEMA) {
                val name = spec.table.tableName
                val table = db.getTable(name)
                if (table == null) {
                    add("missing table $name")
                    continue
                }
                checkColumns(spec, table)
                checkIndexes(spec, table)
            }
        }
        return if (problems.isEmpty()) SchemaCheck.Compatible else SchemaCheck.Mismatch(problems)
    }

    private fun MutableList<String>.checkColumns(spec: TableSpec, table: JetTable) {
        val name = spec.table.tableName
        val actual = table.columns.associate { it.name to it.type }
        for ((column, type) in spec.columns) {
            when (val found = actual[column]) {
                null -> add("$name: missing column $column")
                type -> Unit
                else -> add("$name.$column: expected $type, found $found")
            }
        }
        for (extra in actual.keys - spec.columns.map { it.first }.toSet()) add("$name: unexpected column $extra")
        if (table.columns.firstOrNull { it.name == "id" }?.isAutoNumber != true) add("$name.id is not an autonumber")
    }

    private fun MutableList<String>.checkIndexes(spec: TableSpec, table: JetTable) {
        val name = spec.table.tableName
        // X-Cash databases may have a unique index on `id` without the primary-key flag,
        // so accept either a true PK or any unique ascending index on `id` alone.
        val hasIdIndex = table.indexes.any { idx ->
            idx.isUnique && idx.singleColumn() == ("id" to true)
        }
        if (!hasIdIndex) add("$name: unique index on id not found")
    }

    private fun JetIndex.singleColumn(): Pair<String, Boolean>? =
        columns.singleOrNull()?.let { it.name to it.isAscending }
}
