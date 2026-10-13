// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.ChangeSet
import kalita.storage.api.DbLocation
import kalita.storage.api.FieldChange
import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.SubmitResult
import kalita.storage.api.SyncStatus
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.createXCashSchemaFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class LocalStorageSessionTest {
    private fun cs(vararg ops: Op) = ChangeSet(UUID.randomUUID(), "test", null, Instant.EPOCH, "t", ops.toList())

    private fun location(dir: Path) =
        DbLocation.Local(createXCashSchemaFixture(dir.resolve("Data.mdb").toFile()), dir.resolve("data").toFile())

    @Test
    fun `submit applies the change and refreshes the snapshot`(@TempDir dir: Path) {
        LocalStorageProvider.open(location(dir)).use { s ->
            s.status shouldBe SyncStatus.Local
            s.snapshot.rows(Table.Accounts) shouldBe emptyList()

            val applied = s.submit(cs(Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Cash", "del" to false))))
                .shouldBeInstanceOf<SubmitResult.Applied>()
            val id = applied.tempKeys.getValue(TempKey("a"))
            s.snapshot.row(Table.Accounts, id)!!["Name"] shouldBe "Cash"
        }
    }

    @Test
    fun `conflicting submit leaves the snapshot unchanged`(@TempDir dir: Path) {
        LocalStorageProvider.open(location(dir)).use { s ->
            val before = s.snapshot
            val r = s.submit(cs(Op.Update(Table.Accounts, RowRef.Existing(42), mapOf("Name" to FieldChange("x", "y")))))
            r.shouldBeInstanceOf<SubmitResult.Conflicts>()
            s.snapshot shouldBe before
        }
    }

    @Test
    fun `refresh picks up an external write exactly once`(@TempDir dir: Path) {
        val loc = location(dir)
        LocalStorageProvider.open(loc).use { s ->
            s.refresh() shouldBe false
            // A second writer (stand-in for the original X-Cash) edits the file behind the session's back.
            kalita.storage.mdb.AtomicCommit.commit(
                loc.file,
                listOf(cs(Op.Insert(Table.People, TempKey("p"), mapOf("Name" to "Ann", "del" to false)))),
            )
            s.snapshot.rows(Table.People) shouldBe emptyList()
            s.refresh() shouldBe true
            s.snapshot.rows(Table.People).map { it["Name"] } shouldBe listOf("Ann")
            s.refresh() shouldBe false
        }
    }

    @Test
    fun `second session on the same file is read-only and refuses writes`(@TempDir dir: Path) {
        val loc = location(dir)
        LocalStorageProvider.open(loc).use {
            LocalStorageProvider.open(loc.copy(dataDirectory = dir.resolve("other").toFile())).use { second ->
                second.status.shouldBeInstanceOf<SyncStatus.ReadOnly>()
                second.submit(cs(Op.Insert(Table.People, TempKey("p"), mapOf("Name" to "X", "del" to false))))
                    .shouldBeInstanceOf<SubmitResult.Failed>()
            }
        }
    }
}
