// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kalita.storage.mdb.UpgradeResult
import kalita.storage.mdb.XCashCompat
import kalita.storage.mdb.XCashCompatReport
import java.io.PrintStream

/** `kalita xcash` — parent of `check`/`upgrade` (dev_plan.md §3.8: opening a database with the original X-Cash 2.1.1.54). */
internal class XCashCommand : CliktCommand(name = "xcash") {
    override fun run() = Unit
}

/** `kalita xcash check --db <path>` — exit 0 when the original X-Cash can open the file, 1 when service tables are missing. */
internal class XCashCheckCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "check") {
    private val dbPath by option("--db").required()

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        when (val report = XCashCompat.check(file)) {
            XCashCompatReport.Compatible -> out.println("OK: the original X-Cash 2.1.1.54 can open this database.")
            is XCashCompatReport.Incompatible -> {
                out.println("The original X-Cash 2.1.1.54 will refuse to open this database.")
                if (report.missing.isNotEmpty()) out.println("Missing tables: ${report.missing.joinToString()}.")
                if (report.missingColumns.isNotEmpty()) out.println("Missing columns: ${report.missingColumns.joinToString()}.")
                out.println("Run `kalita xcash upgrade --db $dbPath` to add them (a backup copy is made first).")
                throw ProgramResult(1)
            }
        }
    }
}

/**
 * `kalita xcash upgrade --db <path> [--reference <pristine Data.mdb>]` — adds the service tables the original X-Cash needs.
 * With `--reference` (the `Data.mdb` from the user's own X-Cash installation) it also replaces the screen-description tables
 * (`_Tables`, `_Columns`, `_Objects`, `_Registers*`) with the reference's rows — needed when 2.1.1.54 opens the database
 * but shows a blank «Доходы/Расходы» tab. Application data is never touched.
 */
internal class XCashUpgradeCommand(private val out: PrintStream, private val err: PrintStream) : CliktCommand(name = "upgrade") {
    private val dbPath by option("--db").required()
    private val referencePath by option("--reference")

    override fun run() {
        val file = requireDbFile(dbPath, err) ?: throw ProgramResult(2)
        val reference = referencePath?.let { requireDbFile(it, err) ?: throw ProgramResult(2) }
        when (val result = XCashCompat.upgrade(file, reference)) {
            UpgradeResult.NothingToDo -> out.println("Nothing to do: the database already has every table the original X-Cash needs.")
            is UpgradeResult.Upgraded -> {
                out.println("Added ${result.added.size} tables: ${result.added.joinToString()}.")
                out.println("Added ${result.addedColumns.size} columns to existing service tables: ${result.addedColumns.joinToString()}.")
                out.println("Backup of the previous file: ${result.backup}")
            }
            is UpgradeResult.Failed -> {
                err.println("upgrade failed, the database was not changed: ${result.reason}")
                throw ProgramResult(2)
            }
        }
    }
}
