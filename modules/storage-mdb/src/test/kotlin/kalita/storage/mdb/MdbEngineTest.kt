// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.ChangeSet
import kalita.storage.api.Conflict
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

class MdbEngineTest {
    private val day = LocalDateTime.of(2026, 10, 12, 9, 30, 15, 123_000_000)

    private fun cs(vararg ops: Op) = ChangeSet(UUID.randomUUID(), "test", null, Instant.EPOCH, "test", ops.toList())

    private fun newDb(dir: Path) = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())

    private fun rows(file: File, table: Table): List<Map<String, Any?>> =
        Jackcess.open(file).use { db -> db.getTable(table.tableName).map { it.toMap() } }

    @Test
    fun `fresh fixture matches the known schema`(@TempDir dir: Path) {
        Jackcess.open(newDb(dir)).use { SchemaFingerprint.check(it) shouldBe SchemaCheck.Compatible }
    }

    @Test
    fun `fingerprint reports missing and mistyped columns`(@TempDir dir: Path) {
        val file = dir.resolve("odd.mdb").toFile()
        createOperationsFixture(file, 1)
        Jackcess.open(file).use {
            val check = SchemaFingerprint.check(it)
            check.shouldBeInstanceOf<SchemaCheck.Mismatch>().problems.any { p -> p.contains("missing table GroupBudget") } shouldBe true
        }
    }

    @Test
    fun `engine refuses to write an unknown schema`(@TempDir dir: Path) {
        val file = createOperationsFixture(dir.resolve("odd.mdb").toFile(), 1)
        runCatching { MdbEngine.apply(file, emptyList()) }.isFailure shouldBe true
    }

    @Test
    fun `insert resolves temp keys across tables and truncates time to seconds`(@TempDir dir: Path) {
        val file = newDb(dir)
        val acc = TempKey("acc")
        val op = TempKey("op")
        val result = MdbEngine.apply(
            file,
            listOf(
                cs(
                    Op.Insert(Table.Accounts, acc, mapOf("Name" to "Карта", "del" to false)),
                    Op.Insert(
                        Table.Operations, op,
                        mapOf(
                            "Date" to day, "Summa" to BigDecimal("350.50"), "AccountFrom" to acc,
                            "Comment" to "Продукты", "del" to false,
                        ),
                    ),
                ),
            ),
        )
        result.ok shouldBe true
        val accId = result.tempKeys.getValue(acc)
        val row = rows(file, Table.Operations).single()
        row["id"] shouldBe result.tempKeys.getValue(op).toInt()
        row["AccountFrom"] shouldBe accId.toInt()
        row["Date"] shouldBe day.withNano(0)
        (row["Summa"] as BigDecimal).compareTo(BigDecimal("350.50")) shouldBe 0
    }

    @Test
    fun `update soft delete and hard delete apply when base values match`(@TempDir dir: Path) {
        val file = newDb(dir)
        val a = TempKey("a")
        val r = TempKey("r")
        val id = MdbEngine.apply(
            file,
            listOf(
                cs(
                    Op.Insert(Table.Accounts, a, mapOf("Name" to "A", "del" to false)),
                    Op.Insert(Table.Rates, r, mapOf("Currency_ID" to 1L, "Course" to 90.5, "del" to false)),
                ),
            ),
        ).tempKeys
        val result = MdbEngine.apply(
            file,
            listOf(
                cs(Op.Update(Table.Accounts, RowRef.Existing(id.getValue(a)), mapOf("Name" to FieldChange("A", "B")))),
                cs(Op.SetDeleted(Table.Accounts, RowRef.Existing(id.getValue(a)), base = false, new = true)),
                cs(Op.HardDelete(Table.Rates, RowRef.Existing(id.getValue(r)), mapOf("Course" to 90.5))),
            ),
        )
        result.ok shouldBe true
        rows(file, Table.Accounts).single().let { it["Name"] shouldBe "B"; it["del"] shouldBe true }
        rows(file, Table.Rates).shouldBeEmpty()
    }

    @Test
    fun `stale base value is reported as a field conflict and nothing later is applied`(@TempDir dir: Path) {
        val file = newDb(dir)
        val a = TempKey("a")
        val id = MdbEngine.apply(file, listOf(cs(Op.Insert(Table.Accounts, a, mapOf("Name" to "A", "del" to false))))).tempKeys.getValue(a)
        val ref = RowRef.Existing(id)
        val result = MdbEngine.apply(
            file,
            listOf(
                cs(Op.Update(Table.Accounts, ref, mapOf("Name" to FieldChange("stale", "X")))),
                cs(Op.SetDeleted(Table.Accounts, ref, base = false, new = true)),
            ),
        )
        result.conflicts shouldHaveSize 1
        result.conflicts.single().shouldBeInstanceOf<Conflict.FieldChanged>().column shouldBe "Name"
        rows(file, Table.Accounts).single().let { it["Name"] shouldBe "A"; it["del"] shouldBe false }
    }

    @Test
    fun `missing row and unknown column are conflicts`(@TempDir dir: Path) {
        val file = newDb(dir)
        MdbEngine.apply(file, listOf(cs(Op.SetDeleted(Table.Accounts, RowRef.Existing(999), false, true))))
            .conflicts.single().shouldBeInstanceOf<Conflict.RowMissing>()
        MdbEngine.apply(file, listOf(cs(Op.Insert(Table.Accounts, TempKey("x"), mapOf("Bogus" to 1)))))
            .conflicts.single().shouldBeInstanceOf<Conflict.UnknownColumn>()
    }

    @Test
    fun `update and soft delete reach a row inserted earlier in the same batch`(@TempDir dir: Path) {
        val file = newDb(dir)
        val a = TempKey("a")
        val b = TempKey("b")
        val o = TempKey("o")
        val result = MdbEngine.apply(
            file,
            listOf(
                cs(
                    Op.Insert(Table.Accounts, a, mapOf("Name" to "A", "del" to false)),
                    Op.Insert(Table.Accounts, b, mapOf("Name" to "B", "del" to false)),
                    Op.Insert(Table.Operations, o, mapOf("AccountFrom" to a, "del" to false)),
                ),
                cs(
                    Op.Update(Table.Operations, RowRef.New(o), mapOf("AccountFrom" to FieldChange(a, b))),
                    Op.SetDeleted(Table.Accounts, RowRef.New(a), base = false, new = true),
                ),
            ),
        )
        result.ok shouldBe true
        rows(file, Table.Operations).single()["AccountFrom"] shouldBe result.tempKeys.getValue(b).toInt()
        rows(file, Table.Accounts).first { it["Name"] == "A" }["del"] shouldBe true
    }

    @Test
    fun `money and numbers compare by value whatever their JVM type`(@TempDir dir: Path) {
        val file = newDb(dir)
        val o = TempKey("o")
        val r = TempKey("r")
        val ids = MdbEngine.apply(
            file,
            listOf(
                cs(
                    Op.Insert(Table.Operations, o, mapOf("Summa" to BigDecimal("350.5"), "Currency_ID" to 2, "del" to false)),
                    Op.Insert(Table.Rates, r, mapOf("Course" to 90.5, "del" to false)),
                ),
            ),
        ).tempKeys
        val result = MdbEngine.apply(
            file,
            listOf(
                cs(
                    Op.Update(
                        Table.Operations, RowRef.Existing(ids.getValue(o)),
                        mapOf("Summa" to FieldChange(350.5, BigDecimal("1")), "Currency_ID" to FieldChange(2L, 3L)),
                    ),
                    Op.HardDelete(Table.Rates, RowRef.Existing(ids.getValue(r)), mapOf("Course" to BigDecimal("90.50"))),
                ),
            ),
        )
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `soft and hard delete with stale base values are conflicts`(@TempDir dir: Path) {
        val file = newDb(dir)
        val a = TempKey("a")
        val r = TempKey("r")
        val ids = MdbEngine.apply(
            file,
            listOf(
                cs(
                    Op.Insert(Table.Accounts, a, mapOf("Name" to "A", "del" to false)),
                    Op.Insert(Table.Rates, r, mapOf("Course" to 90.5, "del" to false)),
                ),
            ),
        ).tempKeys
        MdbEngine.apply(file, listOf(cs(Op.SetDeleted(Table.Accounts, RowRef.Existing(ids.getValue(a)), base = true, new = false))))
            .conflicts.single().shouldBeInstanceOf<Conflict.FieldChanged>().column shouldBe "del"
        MdbEngine.apply(file, listOf(cs(Op.HardDelete(Table.Rates, RowRef.Existing(ids.getValue(r)), mapOf("Course" to 91.0)))))
            .conflicts.single().shouldBeInstanceOf<Conflict.FieldChanged>().column shouldBe "Course"
        rows(file, Table.Rates) shouldHaveSize 1
    }

    @Test
    fun `unknown column in an update is a conflict`(@TempDir dir: Path) {
        val file = newDb(dir)
        val a = TempKey("a")
        val id = MdbEngine.apply(file, listOf(cs(Op.Insert(Table.Accounts, a, mapOf("Name" to "A"))))).tempKeys.getValue(a)
        MdbEngine.apply(file, listOf(cs(Op.Update(Table.Accounts, RowRef.Existing(id), mapOf("Bogus" to FieldChange(null, 1))))))
            .conflicts.single().shouldBeInstanceOf<Conflict.UnknownColumn>()
    }

    @Test
    fun `a temp key used twice in one batch is rejected`(@TempDir dir: Path) {
        val file = newDb(dir)
        val insert = Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "A"))
        shouldThrow<IllegalArgumentException> { MdbEngine.apply(file, listOf(cs(insert), cs(insert))) }
    }

    @Test
    fun `primary key cannot be updated`(@TempDir dir: Path) {
        val file = newDb(dir)
        val a = TempKey("a")
        val id = MdbEngine.apply(file, listOf(cs(Op.Insert(Table.Accounts, a, mapOf("Name" to "A"))))).tempKeys.getValue(a)
        shouldThrow<IllegalArgumentException> {
            MdbEngine.apply(file, listOf(cs(Op.Update(Table.Accounts, RowRef.Existing(id), mapOf("id" to FieldChange(id, 5L))))))
        }
    }

    @Test
    fun `forward temp key reference is a conflict`(@TempDir dir: Path) {
        val file = newDb(dir)
        val result = MdbEngine.apply(
            file,
            listOf(cs(Op.Insert(Table.Operations, TempKey("o"), mapOf("AccountFrom" to TempKey("later"))))),
        )
        result.conflicts.single().shouldBeInstanceOf<Conflict.UnresolvedReference>()
    }
}
