// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

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
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class AtomicCommitTest {
    private fun cs(vararg ops: Op) = ChangeSet(UUID.randomUUID(), "test", null, Instant.EPOCH, "test", ops.toList())

    @Test
    fun `commit replaces the working file and leaves no temp file`(@TempDir dir: Path) {
        val file = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        val key = TempKey("a")

        val result = AtomicCommit.commit(file, listOf(cs(Op.Insert(Table.Accounts, key, mapOf("Name" to "Карта", "del" to false)))))

        result.shouldBeInstanceOf<CommitResult.Committed>().tempKeys.keys shouldBe setOf(key)
        dir.toFile().list()!!.toSet() shouldBe setOf("Data.mdb")
        MdbEngine.apply(file, listOf(cs(Op.SetDeleted(Table.Accounts, RowRef.Existing(1), false, true)))).ok shouldBe true
    }

    @Test
    fun `conflict leaves the working file byte-identical`(@TempDir dir: Path) {
        val file = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        AtomicCommit.commit(file, listOf(cs(Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "A", "del" to false)))))
        val before = file.readBytes()

        val result = AtomicCommit.commit(
            file,
            listOf(cs(Op.Update(Table.Accounts, RowRef.Existing(1), mapOf("Name" to FieldChange("stale", "B"))))),
        )

        result.shouldBeInstanceOf<CommitResult.Conflicts>().conflicts.single().shouldBeInstanceOf<Conflict.FieldChanged>()
        file.readBytes().contentEquals(before) shouldBe true
        dir.toFile().list()!!.toSet() shouldBe setOf("Data.mdb")
    }

    @Test
    fun `failure on an unknown schema leaves the file untouched`(@TempDir dir: Path) {
        val file = createOperationsFixture(dir.resolve("odd.mdb").toFile(), 3)
        val before = file.readBytes()

        AtomicCommit.commit(file, emptyList()).shouldBeInstanceOf<CommitResult.Failed>()

        file.readBytes().contentEquals(before) shouldBe true
        dir.toFile().list()!!.toSet() shouldBe setOf("odd.mdb")
    }

    @Test
    fun `missing working file is a failure not a crash`(@TempDir dir: Path) {
        AtomicCommit.commit(dir.resolve("none.mdb").toFile(), emptyList()).shouldBeInstanceOf<CommitResult.Failed>()
    }
}
