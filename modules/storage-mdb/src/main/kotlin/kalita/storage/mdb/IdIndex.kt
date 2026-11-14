// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

/**
 * Lookup by the `id` column that works on real X-Cash files too: those may carry only a unique
 * index on `id` without the primary-key flag (see [SchemaFingerprint]), where Jackcess's
 * `createPrimaryKeyCursor` / `findRowByPrimaryKey` fail with "No primary key index found".
 */
internal fun idIndex(table: JetTable): JetIndex =
    table.indexes.firstOrNull { it.isPrimaryKey }
        ?: table.indexes.firstOrNull { idx -> idx.isUnique && idx.columns.singleOrNull()?.name == "id" }
        ?: throw IllegalStateException("No index on id found (table ${table.name})")

internal fun idCursor(table: JetTable): IndexCursor = Jackcess.cursor(idIndex(table))

internal fun findRowById(table: JetTable, id: Any): JetRow? {
    val cursor = idCursor(table)
    return if (cursor.findFirstRow(mapOf("id" to id))) cursor.currentRow else null
}
