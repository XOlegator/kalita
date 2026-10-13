// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.kotest.matchers.shouldBe
import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class MdbSnapshotReaderTest {
    @Test
    fun `empty schema fixture gives empty tables for every application table`(@TempDir dir: Path) {
        val snapshot = MdbSnapshotReader.read(createXCashSchemaFixture(dir.resolve("Data.mdb").toFile()))
        Table.entries.forEach { snapshot.rows(it) shouldBe emptyList() }
    }

    @Test
    fun `written rows are read back with canonical types and soft-deleted rows included`(@TempDir dir: Path) {
        val file = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        val date = LocalDateTime.of(2026, 10, 12, 9, 30, 15)
        val cs = ChangeSet(
            UUID.randomUUID(), "test", null, Instant.EPOCH, "seed",
            listOf(
                Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Cash", "del" to false)),
                Op.Insert(Table.Accounts, TempKey("b"), mapOf("Name" to "Old", "del" to true)),
                Op.Insert(
                    Table.Operations, TempKey("o"),
                    mapOf("Date" to date, "Summa" to BigDecimal("350.50"), "AccountFrom" to TempKey("a"), "Comment" to "bread", "del" to false),
                ),
            ),
        )
        val result = AtomicCommit.commit(file, listOf(cs)) as CommitResult.Committed

        val snapshot = MdbSnapshotReader.read(file)
        snapshot.rows(Table.Accounts).map { it["Name"] } shouldBe listOf("Cash", "Old")
        snapshot.row(Table.Accounts, result.tempKeys.getValue(TempKey("b")))!!["del"] shouldBe true
        val op = snapshot.row(Table.Operations, result.tempKeys.getValue(TempKey("o")))!!
        op["Date"] shouldBe date
        (op["Summa"] as BigDecimal).compareTo(BigDecimal("350.50")) shouldBe 0
        op["AccountFrom"] shouldBe result.tempKeys.getValue(TempKey("a"))
        op["Comment"] shouldBe "bread"
        snapshot.row(Table.Accounts, 999) shouldBe null
    }
}
