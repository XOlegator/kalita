// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.devcli

import kalita.storage.api.Table
import kalita.storage.mdb.MdbSnapshotReader
import kalita.storage.mdb.MdbVerifier
import kalita.storage.mdb.RowDiff
import kalita.storage.mdb.SnapshotDiff
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

private const val USAGE = """usage:
  dump <Data.mdb>            print every application table
  verify <Data.mdb>          check schema, indexes, ids, references (exit 1 on errors)
  diff <old.mdb> <new.mdb>   row-level difference (exit 1 if they differ)"""

/** Developer utility: dump/verify/diff (dev_plan.md §9.2, stage 1). Never writes to the files it is given. */
fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
    fun file(path: String): File? = File(path).takeIf { it.isFile } ?: run { err.println("no such file: $path"); null }
    return try {
        when {
            args.size == 2 && args[0] == "dump" -> file(args[1])?.let { dump(it, out); 0 } ?: 2
            args.size == 2 && args[0] == "verify" -> file(args[1])?.let { verify(it, out) } ?: 2
            args.size == 3 && args[0] == "diff" -> {
                val a = file(args[1]); val b = file(args[2])
                if (a != null && b != null) diff(a, b, out) else 2
            }
            else -> { err.println(USAGE); 2 }
        }
    } catch (e: Exception) {
        err.println("error: ${e.message ?: e.javaClass.simpleName}")
        2
    }
}

private fun dump(file: File, out: PrintStream) {
    val snapshot = MdbSnapshotReader.read(file)
    for (table in Table.entries) {
        val rows = snapshot.rows(table)
        out.println("== ${table.tableName} (${rows.size} rows)")
        rows.forEach { row -> out.println(row.entries.joinToString("\t") { "${it.key}=${it.value}" }) }
    }
}

private fun verify(file: File, out: PrintStream): Int {
    val report = MdbVerifier.verify(file)
    report.errors.forEach { out.println("ERROR $it") }
    report.warnings.forEach { out.println("WARN  $it") }
    out.println(if (report.ok) "OK (${report.warnings.size} warnings)" else "FAILED (${report.errors.size} errors)")
    return if (report.ok) 0 else 1
}

private fun diff(a: File, b: File, out: PrintStream): Int {
    val diffs = SnapshotDiff.diff(MdbSnapshotReader.read(a), MdbSnapshotReader.read(b))
    for (d in diffs) {
        val head = "${d.table.tableName}#${d.id}"
        when (d) {
            is RowDiff.Added -> out.println("+ $head ${d.row}")
            is RowDiff.Removed -> out.println("- $head ${d.row}")
            is RowDiff.Changed -> out.println("~ $head " + d.changes.entries.joinToString(", ") { "${it.key}: ${it.value.first} -> ${it.value.second}" })
        }
    }
    out.println(if (diffs.isEmpty()) "identical" else "${diffs.size} differences")
    return if (diffs.isEmpty()) 0 else 1
}

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out, System.err))
}
