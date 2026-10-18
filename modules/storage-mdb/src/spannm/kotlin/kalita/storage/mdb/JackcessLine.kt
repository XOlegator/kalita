// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import java.io.File

// The only file that names the Jackcess line this build compiles against (dev_plan.md §8.3, §9.5):
// io.github.spannm:jackcess 5.1.x — the default line. Its twin in src/upstream/ covers
// com.healthmarketscience.jackcess 5.0.x. The two lines differ in package and in builder-method
// naming (`withX` here, `setX` upstream); everything else storage-mdb uses is identical, so the
// shared code goes through these aliases and helpers and never imports Jackcess directly. Keep the
// two files declaration-for-declaration in sync — the `storage-upstream` CI job compiles the other.

internal typealias Database = io.github.spannm.jackcess.Database
internal typealias JetTable = io.github.spannm.jackcess.Table
internal typealias JetIndex = io.github.spannm.jackcess.Index
internal typealias DataType = io.github.spannm.jackcess.DataType
internal typealias Cursor = io.github.spannm.jackcess.Cursor
internal typealias IndexCursor = io.github.spannm.jackcess.IndexCursor
internal typealias JetRow = io.github.spannm.jackcess.Row
internal typealias TableBuilder = io.github.spannm.jackcess.TableBuilder
internal typealias ColumnBuilder = io.github.spannm.jackcess.ColumnBuilder
internal typealias IndexBuilder = io.github.spannm.jackcess.IndexBuilder

internal object Jackcess {
    /** Human-readable name of the compiled-in line, for diagnostics and test reports. */
    const val LINE: String = "spannm"

    /** Placeholder that makes `Table.addRow` assign the next autonumber. */
    val AUTO_NUMBER: Any = io.github.spannm.jackcess.Column.AUTO_NUMBER

    const val PRIMARY_KEY_NAME: String = io.github.spannm.jackcess.IndexBuilder.PRIMARY_KEY_NAME

    // Charset is deliberately never set (dev_plan.md §3.2 item 6): windows-1251 breaks catalog lookup.
    fun open(file: File, readOnly: Boolean = false, autoSync: Boolean = true): Database =
        io.github.spannm.jackcess.DatabaseBuilder(file).withReadOnly(readOnly).withAutoSync(autoSync).open().localDates()

    /** New, empty Jet 4 (Access 2000) file — the format X-Cash itself uses. */
    fun create(file: File): Database =
        io.github.spannm.jackcess.DatabaseBuilder.create(io.github.spannm.jackcess.Database.FileFormat.V2000, file).localDates()

    fun autoNumber(column: ColumnBuilder): ColumnBuilder = column.withAutoNumber(true)

    fun indexOn(index: IndexBuilder, ascending: Boolean, vararg columns: String): IndexBuilder =
        index.withColumns(ascending, *columns)

    fun primaryKey(index: IndexBuilder): IndexBuilder = index.withPrimaryKey()

    fun cursor(table: JetTable): Cursor = io.github.spannm.jackcess.CursorBuilder.createCursor(table)

    fun cursor(index: JetIndex): IndexCursor = io.github.spannm.jackcess.CursorBuilder.createCursor(index)

    fun primaryKeyCursor(table: JetTable): IndexCursor = io.github.spannm.jackcess.CursorBuilder.createPrimaryKeyCursor(table)

    fun findRowByPrimaryKey(table: JetTable, id: Any): JetRow? =
        io.github.spannm.jackcess.CursorBuilder.findRowByPrimaryKey(table, id)

    // Explicit rather than relying on the line's default, which a JVM system property can change:
    // all of storage-mdb reads and writes java.time.LocalDateTime (§9.5).
    private fun Database.localDates(): Database =
        apply { dateTimeType = io.github.spannm.jackcess.DateTimeType.LOCAL_DATE_TIME }
}
