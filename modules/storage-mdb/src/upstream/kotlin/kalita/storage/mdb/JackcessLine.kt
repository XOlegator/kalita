// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import java.io.File

// The only file that names the Jackcess line this build compiles against (dev_plan.md §8.3, §9.5):
// com.healthmarketscience.jackcess 5.0.x — the upstream insurance line, selected with
// `-Pkalita.jackcess=upstream`. Twin of src/spannm/ (the default line); keep the two files
// declaration-for-declaration in sync. Upstream names its builder methods `setX` (the fork renamed
// them to `withX`) and `IndexBuilder.addColumns` for the fork's `withColumns`.

internal typealias Database = com.healthmarketscience.jackcess.Database
internal typealias JetTable = com.healthmarketscience.jackcess.Table
internal typealias JetIndex = com.healthmarketscience.jackcess.Index
internal typealias DataType = com.healthmarketscience.jackcess.DataType
internal typealias Cursor = com.healthmarketscience.jackcess.Cursor
internal typealias IndexCursor = com.healthmarketscience.jackcess.IndexCursor
internal typealias JetRow = com.healthmarketscience.jackcess.Row
internal typealias TableBuilder = com.healthmarketscience.jackcess.TableBuilder
internal typealias ColumnBuilder = com.healthmarketscience.jackcess.ColumnBuilder
internal typealias IndexBuilder = com.healthmarketscience.jackcess.IndexBuilder

internal object Jackcess {
    /** Human-readable name of the compiled-in line, for diagnostics and test reports. */
    const val LINE: String = "upstream"

    /** Placeholder that makes `Table.addRow` assign the next autonumber. */
    val AUTO_NUMBER: Any = com.healthmarketscience.jackcess.Column.AUTO_NUMBER

    const val PRIMARY_KEY_NAME: String = com.healthmarketscience.jackcess.IndexBuilder.PRIMARY_KEY_NAME

    // Charset is deliberately never set (dev_plan.md §3.2 item 6): windows-1251 breaks catalog lookup.
    fun open(file: File, readOnly: Boolean = false, autoSync: Boolean = true): Database =
        com.healthmarketscience.jackcess.DatabaseBuilder(file).setReadOnly(readOnly).setAutoSync(autoSync).open().localDates()

    /** New, empty Jet 4 (Access 2000) file — the format X-Cash itself uses. */
    fun create(file: File): Database =
        com.healthmarketscience.jackcess.DatabaseBuilder.create(com.healthmarketscience.jackcess.Database.FileFormat.V2000, file).localDates()

    fun autoNumber(column: ColumnBuilder): ColumnBuilder = column.setAutoNumber(true)

    fun indexOn(index: IndexBuilder, ascending: Boolean, vararg columns: String): IndexBuilder =
        index.addColumns(ascending, *columns)

    fun primaryKey(index: IndexBuilder): IndexBuilder = index.setPrimaryKey()

    fun cursor(table: JetTable): Cursor = com.healthmarketscience.jackcess.CursorBuilder.createCursor(table)

    fun cursor(index: JetIndex): IndexCursor = com.healthmarketscience.jackcess.CursorBuilder.createCursor(index)

    fun primaryKeyCursor(table: JetTable): IndexCursor = com.healthmarketscience.jackcess.CursorBuilder.createPrimaryKeyCursor(table)

    fun findRowByPrimaryKey(table: JetTable, id: Any): JetRow? =
        com.healthmarketscience.jackcess.CursorBuilder.findRowByPrimaryKey(table, id)

    // Explicit rather than relying on the line's default, which a JVM system property can change:
    // all of storage-mdb reads and writes java.time.LocalDateTime (§9.5).
    private fun Database.localDates(): Database =
        apply { dateTimeType = com.healthmarketscience.jackcess.DateTimeType.LOCAL_DATE_TIME }
}
