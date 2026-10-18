// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.ChangeSet
import kalita.storage.api.FieldChange
import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

internal data class TableDump(val rows: List<Map<String, Any?>>, val indexWalks: Map<String, Int>)
internal typealias JetDump = Map<String, TableDump>

/** Every user table as the compiled-in line sees it — the counterpart of [OtherLine.dump]. */
private fun activeLineDump(file: File): JetDump =
    Jackcess.open(file, readOnly = true).use { db ->
        db.tableNames.sorted().associateWith { name ->
            val table = db.getTable(name)
            TableDump(
                rows = table.map { LinkedHashMap(it) },
                indexWalks = table.indexes.associate { it.name to Jackcess.cursor(it).count() },
            )
        }
    }

// dev_plan.md §8.3: both Jackcess lines are kept buildable as insurance. Switching is only safe if a
// Data.mdb written by one line reads back identically through the other — checked here in both
// directions, since CI runs this suite once per line (-Pkalita.jackcess=spannm|upstream).
class CrossLineTest {
    @Test
    fun `the compiled-in line is the one the build selected`() {
        Jackcess.LINE shouldBe (System.getProperty("kalita.jackcess") ?: Jackcess.LINE)
        (OtherLine.NAME == Jackcess.LINE) shouldBe false
    }

    @Test
    fun `scenario 8_2 written by this line reads back identically through the other`(@TempDir dir: Path) {
        val file = dir.resolve("scenario82.mdb").toFile()
        val result = runXCashWriteScenario(file)

        val other = OtherLine.dump(file)
        other shouldBe activeLineDump(file)
        other.getValue(OPERATIONS_TABLE).rows.size shouldBe result.operations
        other.getValue("Course").indexWalks.values.toSet() shouldBe setOf(result.courses)
    }

    @Test
    fun `change sets committed by this line read back identically through the other`(@TempDir dir: Path) {
        val file = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        val date = LocalDateTime.of(2026, 10, 18, 23, 59, 59)
        val seed = ChangeSet(
            UUID.randomUUID(), "test", null, Instant.EPOCH, "seed",
            listOf(
                Op.Insert(Table.Currencies, TempKey("rsd"), mapOf("Name" to "Динар", "CB_ID" to "RSD", "del" to false)),
                Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Новчаник ёЁ", "del" to false)),
                Op.Insert(
                    Table.Operations, TempKey("o"),
                    mapOf(
                        "Date" to date, "Summa" to BigDecimal("1234.5678"), "Currency_ID" to TempKey("rsd"),
                        "AccountFrom" to TempKey("a"), "Comment" to "Хлеб ".repeat(1_000), "del" to false,
                    ),
                ),
                Op.Insert(Table.Rates, TempKey("r"), mapOf("Currency_ID" to TempKey("rsd"), "Date" to date, "Course" to 0.85, "del" to false)),
            ),
        )
        val ids = (AtomicCommit.commit(file, listOf(seed)) as CommitResult.Committed).tempKeys
        val edit = ChangeSet(
            UUID.randomUUID(), "test", null, Instant.EPOCH, "edit",
            listOf(
                Op.Update(
                    Table.Operations, RowRef.Existing(ids.getValue(TempKey("o"))),
                    mapOf("Comment" to FieldChange("Хлеб ".repeat(1_000), null)),
                ),
                Op.SetDeleted(Table.Accounts, RowRef.Existing(ids.getValue(TempKey("a"))), base = false, new = true),
            ),
        )
        AtomicCommit.commit(file, listOf(edit)).shouldBeInstanceOf<CommitResult.Committed>()

        val other = OtherLine.dump(file)
        other shouldBe activeLineDump(file)
        val op = other.getValue(Table.Operations.tableName).rows.single()
        op["Date"] shouldBe date
        (op["Summa"] as BigDecimal).compareTo(BigDecimal("1234.5678")) shouldBe 0
        op["Comment"] shouldBe null
        other.getValue(Table.Accounts.tableName).rows.single()["del"] shouldBe true
    }

    @Test
    fun `dates stay LocalDateTime even when a JVM property asks Jackcess for java util Date`(@TempDir dir: Path) {
        val keys = listOf("jackcess.dateTimeType", "com.healthmarketscience.jackcess.dateTimeType") // spannm, upstream
        val saved = keys.associateWith { System.getProperty(it) }
        try {
            keys.forEach { System.setProperty(it, "DATE") }
            val file = dir.resolve("dates.mdb").toFile()
            runXCashWriteScenario(file)
            Jackcess.open(file, readOnly = true).use { db ->
                db.getTable(OPERATIONS_TABLE).first()["Date"].shouldBeInstanceOf<LocalDateTime>()
            }
        } finally {
            saved.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
    }
}
