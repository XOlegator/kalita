// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.CommitResult
import kalita.storage.mdb.createXCashSchemaFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class LocalDatabaseTest {
    private fun addAccount(name: String) = ChangeSet(
        UUID.randomUUID(), "test", null, Instant.EPOCH, "add $name",
        listOf(Op.Insert(Table.Accounts, TempKey(name), mapOf("Name" to name, "del" to false))),
    )

    private fun open(dir: Path, keep: Int = 10): LocalDatabase {
        val db = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        return LocalDatabase.open(db, dir.resolve("data").toFile(), keep)
    }

    @Test
    fun `submit commits and backs up the previous state`(@TempDir dir: Path) {
        open(dir).use { db ->
            val outcome = db.submit(listOf(addAccount("A")))
            outcome.result.shouldBeInstanceOf<CommitResult.Committed>()
            outcome.externalChange shouldBe false
            db.checkExternalChange() shouldBe false
        }
        dir.resolve("data/backups").toFile().list()!! shouldHaveSize 1
    }

    @Test
    fun `committed change sets are recorded in the history with real ids`(@TempDir dir: Path) {
        val cs = addAccount("A")
        open(dir).use { db ->
            db.submit(listOf(cs))
            val entry = db.history.read().single()
            entry.changeSet shouldBe cs
            entry.tempKeys shouldBe mapOf(TempKey("A") to 1L)
        }
    }

    @Test
    fun `failed or read-only submits leave no history`(@TempDir dir: Path) {
        open(dir).use { db ->
            LocalDatabase.open(db.file, dir.resolve("b").toFile()).use { it.submit(listOf(addAccount("X"))) }
            db.history.read() shouldBe emptyList()
        }
    }

    @Test
    fun `external edit is detected once`(@TempDir dir: Path) {
        open(dir).use { db ->
            db.checkExternalChange() shouldBe false
            db.file.appendBytes(byteArrayOf(0))
            db.checkExternalChange() shouldBe true
            db.checkExternalChange() shouldBe false
        }
    }

    @Test
    fun `submit reports an external change and still commits on the re-read file`(@TempDir dir: Path) {
        open(dir).use { db ->
            db.submit(listOf(addAccount("A")))
            val touched = db.file.lastModified() + 5_000
            db.file.setLastModified(touched)
            val outcome = db.submit(listOf(addAccount("B")))
            outcome.externalChange shouldBe true
            outcome.result.shouldBeInstanceOf<CommitResult.Committed>()
        }
    }

    @Test
    fun `second instance and Jet ldb file make the database read-only`(@TempDir dir: Path) {
        open(dir).use { first ->
            first.mode shouldBe OpenMode.ReadWrite
            LocalDatabase.open(first.file, dir.resolve("b").toFile()).use { second ->
                second.mode.shouldBeInstanceOf<OpenMode.ReadOnly>()
                second.submit(listOf(addAccount("X"))).result.shouldBeInstanceOf<CommitResult.Failed>()
            }
        }
        File(dir.toFile(), "Data.ldb").writeText("")
        LocalDatabase.open(File(dir.toFile(), "Data.mdb"), dir.resolve("b").toFile()).use {
            it.mode.shouldBeInstanceOf<OpenMode.ReadOnly>()
        }
    }

    @Test
    fun `backups are pruned to the configured count`(@TempDir dir: Path) {
        val manager = BackupManager(dir.resolve("bk").toFile(), keep = 3)
        val src = dir.resolve("Data.mdb").toFile().apply { writeText("x") }
        repeat(5) { manager.backup(src, LocalDateTime.of(2026, 10, 12, 10, 0, it)) }
        manager.list("Data") shouldHaveSize 3
        manager.list("Data").first().name shouldBe "Data-20261012-100002.mdb"
    }
}
