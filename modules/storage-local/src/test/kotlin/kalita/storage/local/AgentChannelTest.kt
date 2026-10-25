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
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.createXCashSchemaFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Drives [AgentChannelServer]/[AgentChannelClient] as two separate parties talking over the real
 * local socket (dev_plan.md §12.15, FR-AG6) — no stdio, no subprocess, same spirit as
 * [kalita.app.cli.McpServerTest] driving a real MCP `Server`/`Client` pair in-process.
 */
class AgentChannelTest {
    private fun cs(vararg ops: Op) = ChangeSet(UUID.randomUUID(), "test", null, Instant.EPOCH, "t", ops.toList())

    @Test
    fun `no running server, trySubmit comes back null`(@TempDir dir: Path) {
        val db = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        AgentChannelClient.trySubmit(db, cs(Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Cash", "del" to false)))) shouldBe null
    }

    @Test
    fun `a change set submitted through the client reaches the server's handler and the real database`(@TempDir dir: Path) {
        val db = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        LocalStorageProvider.open(DbLocation.Local(db, dir.resolve("data").toFile())).use { session ->
            val server = AgentChannelServer.start(db) { changeSet -> session.submit(changeSet) }
            checkNotNull(server).use {
                val result = AgentChannelClient.trySubmit(
                    db,
                    cs(Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Cash", "del" to false))),
                )
                val applied = result.shouldBeInstanceOf<SubmitResult.Applied>()
                val id = applied.tempKeys.getValue(TempKey("a"))
                session.snapshot.row(Table.Accounts, id)!!["Name"] shouldBe "Cash"
            }
        }
    }

    @Test
    fun `a conflict from the handler round-trips back through the client unchanged`(@TempDir dir: Path) {
        val db = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        LocalStorageProvider.open(DbLocation.Local(db, dir.resolve("data").toFile())).use { session ->
            AgentChannelServer.start(db) { changeSet -> session.submit(changeSet) }!!.use {
                val result = AgentChannelClient.trySubmit(
                    db,
                    cs(Op.Update(Table.Accounts, RowRef.Existing(42), mapOf("Name" to FieldChange("x", "y")))),
                )
                result.shouldBeInstanceOf<SubmitResult.Conflicts>()
            }
        }
    }

    @Test
    fun `closing the server removes the socket file, trySubmit falls back to null again`(@TempDir dir: Path) {
        val db = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        val server = AgentChannelServer.start(db) { SubmitResult.Failed("unused") }
        checkNotNull(server)
        File(AgentChannel.socketPath(db).toString()).exists() shouldBe true
        server.close()
        File(AgentChannel.socketPath(db).toString()).exists() shouldBe false
        AgentChannelClient.trySubmit(db, cs()) shouldBe null
    }

    @Test
    fun `starting a second server for the same database file replaces the stale socket, not an error`(@TempDir dir: Path) {
        val db = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        val first = AgentChannelServer.start(db) { SubmitResult.Failed("first") }
        checkNotNull(first)
        // Simulates a previous run's socket left behind after an unclean shutdown (same assumption
        // DatabaseLock documents for a stale `.ldb`) rather than first.close() — a clean handover.
        val second = AgentChannelServer.start(db) { SubmitResult.Failed("second") }
        checkNotNull(second).use {
            val result = AgentChannelClient.trySubmit(db, cs())
            result.shouldBeInstanceOf<SubmitResult.Failed>().reason shouldBe "second"
        }
    }
}
