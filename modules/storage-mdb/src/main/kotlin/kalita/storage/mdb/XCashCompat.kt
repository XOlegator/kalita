// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Compatibility of a `Data.mdb` with the *original* X-Cash 2.1.1.54 (dev_plan.md §3.8).
 *
 * Databases created by older X-Cash builds lack service tables (`_Languages`, `_Messages`, …) that
 * 2.1.1.54 reads on start-up; it then stops with «The Microsoft Jet database engine cannot find the input
 * table or query '_Languages'». That is a property of the file, not of anything Kalita wrote: the same
 * error appears on a copy taken before Kalita's first write. [upgrade] adds the missing tables (empty,
 * plus the small system lookups) and touches nothing else.
 */
sealed interface XCashCompatReport {
    /** The file has every service table X-Cash 2.1.1.54 needs. */
    data object Compatible : XCashCompatReport

    /**
     * [missing] tables and [missingColumns] (as `Table.Column`) would make X-Cash 2.1.1.54 stop with an
     * error on start-up or while using the corresponding feature.
     */
    data class Incompatible(val missing: List<String>, val missingColumns: List<String>) : XCashCompatReport
}

sealed interface UpgradeResult {
    data class Upgraded(val added: List<String>, val addedColumns: List<String>, val backup: File) : UpgradeResult
    data object NothingToDo : UpgradeResult
    data class Failed(val reason: String, val cause: Throwable? = null) : UpgradeResult
}

object XCashCompat {
    private class TableDef(val name: String) {
        val columns = mutableListOf<Triple<String, DataType, Int>>()
        val autoNumber = mutableSetOf<String>()
        val indexes = mutableListOf<Triple<String, Boolean, List<String>>>()
        val rows = mutableListOf<List<String?>>()
    }

    private class ColumnDef(val table: String, val name: String, val type: DataType, val size: Int)

    private class Definitions(val tables: List<TableDef>, val columns: List<ColumnDef>)

    private val definitions: Definitions by lazy { parse() }

    fun check(file: File): XCashCompatReport =
        Jackcess.open(file, readOnly = true).use { db ->
            val tables = missing(db).map(TableDef::name)
            val columns = missingColumns(db).map { "${it.table}.${it.name}" }
            if (tables.isEmpty() && columns.isEmpty()) XCashCompatReport.Compatible else XCashCompatReport.Incompatible(tables, columns)
        }

    /**
     * Copy → add tables to the copy → reopen and verify → backup the original → atomic replace
     * (same discipline as [AtomicCommit]). The working file is untouched on any failure.
     */
    fun upgrade(workingFile: File, reference: File? = null): UpgradeResult {
        val target = try {
            workingFile.toPath().toRealPath().toFile()
        } catch (e: IOException) {
            return UpgradeResult.Failed(e.message ?: e.javaClass.simpleName, e)
        }
        val tmp = File(target.parentFile, target.name + ".kalita-tmp")
        try {
            Files.copy(target.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val (added, addedColumns) = Jackcess.open(tmp, autoSync = false).use { db ->
                val todo = missing(db)
                todo.forEach { create(db, it) }
                val columns = missingColumns(db)
                columns.forEach { c ->
                    val column = ColumnBuilder(c.name, c.type)
                    if (c.size > 0) Jackcess.lengthInUnits(column, c.size)
                    column.addToTable(db.getTable(c.table))
                }
                if (reference != null) syncMetadata(db, reference)
                db.flush()
                todo.map(TableDef::name) to columns.map { "${it.table}.${it.name}" }
            }
            if (added.isEmpty() && addedColumns.isEmpty() && reference == null) return UpgradeResult.NothingToDo
            verify(tmp, added, addedColumns)?.let { return UpgradeResult.Failed("verification failed: $it") }
            if (reference != null) verifyMetadata(tmp, reference)?.let { return UpgradeResult.Failed("verification failed: $it") }
            val backup = File(target.parentFile, target.nameWithoutExtension + ".pre-xcash-upgrade-" + STAMP.format(LocalDateTime.now()) + ".mdb")
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return UpgradeResult.Upgraded(added, addedColumns, backup)
        } catch (e: Exception) {
            return UpgradeResult.Failed(e.message ?: e.javaClass.simpleName, e)
        } finally {
            tmp.delete()
        }
    }

    /**
     * The tables that describe X-Cash's own screens (which registers, columns, filters each journal has).
     * Their rows reference each other by id, so they only work as a consistent set: an older database
     * carries the previous generation of the set, and 2.1.1.54 then builds a blank «Доходы/Расходы» tab
     * (verified by swapping them one group at a time). They hold no user data.
     */
    private val METADATA_TABLES = listOf("_Tables", "_Columns", "_Objects", "_Registers", "_RegistersColumns", "_RegistersTables")

    /** Replaces the rows of [METADATA_TABLES] with those of [reference], a pristine X-Cash 2.1.1.54 `Data.mdb`. */
    private fun syncMetadata(db: Database, reference: File) {
        Jackcess.open(reference, readOnly = true).use { ref ->
            db.isAllowAutoNumberInsert = true
            for (name in METADATA_TABLES) {
                val target = db.getTable(name) ?: error("$name is missing from the database")
                val source = ref.getTable(name) ?: error("$name is missing from the reference database")
                val cursor = Jackcess.cursor(target)
                while (cursor.moveToNextRow()) cursor.deleteCurrentRow()
                val known = target.columns.map { it.name }.toSet()
                for (row in source) target.addRowFromMap(row.filterKeys { it in known })
            }
        }
    }

    private fun verifyMetadata(file: File, reference: File): String? =
        Jackcess.open(file, readOnly = true).use { db ->
            Jackcess.open(reference, readOnly = true).use { ref ->
                for (name in METADATA_TABLES) {
                    val expected = ref.getTable(name).rowCount
                    val actual = db.getTable(name).rowCount
                    if (actual != expected) return "$name: expected $expected rows, found $actual"
                    for (index in db.getTable(name).indexes) {
                        val walked = Jackcess.cursor(index).count()
                        if (walked != actual) return "$name: index ${index.name} lists $walked of $actual rows"
                    }
                }
                null
            }
        }

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Raw catalog names, trimmed: old databases hold e.g. `_Conformity ` (trailing space) that the table lookup misses. */
    private fun catalogNames(db: Database): Set<String> =
        db.getSystemTable("MSysObjects").map { it["Name"].toString().trim().lowercase() }.toSet()

    private fun missing(db: Database): List<TableDef> {
        val have = catalogNames(db)
        return definitions.tables.filter { it.name.trim().lowercase() !in have }
    }

    private fun missingColumns(db: Database): List<ColumnDef> =
        definitions.columns.filter { c ->
            val table = db.getTable(c.table)
            table != null && table.columns.none { it.name.equals(c.name, ignoreCase = true) }
        }

    private fun create(db: Database, def: TableDef) {
        val builder = TableBuilder(def.name)
        for ((name, type, size) in def.columns) {
            val column = ColumnBuilder(name, type)
            if (size > 0) Jackcess.lengthInUnits(column, size)
            if (name in def.autoNumber) Jackcess.autoNumber(column)
            builder.addColumn(column)
        }
        for ((name, primary, columns) in def.indexes) {
            val index = Jackcess.indexOn(IndexBuilder(name), true, *columns.toTypedArray())
            builder.addIndex(if (primary) Jackcess.primaryKey(index) else index)
        }
        val table = builder.toTable(db)
        for (row in def.rows) {
            table.addRow(*def.columns.mapIndexed { i, (name, type, _) -> if (name in def.autoNumber) Jackcess.AUTO_NUMBER else convert(row[i], type) }.toTypedArray())
        }
    }

    private fun convert(raw: String?, type: DataType): Any? = when {
        raw == null -> null
        type == DataType.LONG -> raw.toInt()
        type == DataType.BOOLEAN -> raw.toBoolean()
        type == DataType.SHORT_DATE_TIME -> LocalDateTime.parse(raw)
        type == DataType.MONEY -> BigDecimal(raw)
        else -> raw
    }

    private fun verify(file: File, added: List<String>, addedColumns: List<String>): String? =
        Jackcess.open(file, readOnly = true).use { db ->
            for (name in added) {
                val table = db.getTable(name) ?: return "$name missing after upgrade"
                val expected = definitions.tables.first { it.name == name }.rows.size
                if (table.rowCount != expected) return "$name: expected $expected rows, found ${table.rowCount}"
                for (index in table.indexes) {
                    val walked = Jackcess.cursor(index).count()
                    if (walked != table.rowCount) return "$name: index ${index.name} lists $walked of ${table.rowCount} rows"
                }
            }
            for (qualified in addedColumns) {
                val (table, column) = qualified.split('.', limit = 2)
                if (db.getTable(table).columns.none { it.name == column }) return "$qualified missing after upgrade"
            }
            // Every pre-existing application table must still be readable.
            (SchemaFingerprint.check(db) as? SchemaCheck.Mismatch)?.let { return "schema changed: ${it.problems}" }
            null
        }

    private fun unescape(s: String): String? {
        if (s == "\\N") return null
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(
                    when (val n = s[++i]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> n
                    },
                )
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun parse(): Definitions {
        val stream = XCashCompat::class.java.getResourceAsStream("xcash-2.1-extra-tables.tsv")
            ?: error("xcash-2.1-extra-tables.tsv is missing from the classpath")
        val result = mutableListOf<TableDef>()
        val extraColumns = mutableListOf<ColumnDef>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split('\t')
                when (f[0]) {
                    "A" -> extraColumns.add(ColumnDef(f[1], f[2], DataType.valueOf(f[3]), f[4].toInt()))
                    "T" -> result += TableDef(f[1])
                    "C" -> result.last().run {
                        columns += Triple(f[1], DataType.valueOf(f[2]), f[3].toInt())
                        if (f[4] == "1") autoNumber += f[1]
                    }
                    "I" -> result.last().indexes += Triple(f[1], f[2] == "pk", f[3].split(','))
                    "R" -> result.last().rows += f.drop(1).map(::unescape)
                }
            }
        }
        return Definitions(result, extraColumns)
    }
}
