// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.SubmitResult
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.local.AgentChannel
import kalita.storage.local.AgentChannelClient
import kalita.storage.mdb.createXCashSchemaFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * [AppController]'s side of the local channel (dev_plan.md §12.15, FR-AG6): it owns the
 * [kalita.storage.local.AgentChannelServer] for as long as it holds the database open with the
 * write lock, and [AppController.applyRemoteChangeSet] is the handler wired to it.
 */
class AppControllerAgentChannelTest {
    @TempDir
    lateinit var tempDir: Path

    private fun fixture(): File = createXCashSchemaFixture(tempDir.resolve("Data.mdb").toFile())

    private fun controller(): AppController = AppController(AppSettings(tempDir.resolve("config")))

    private fun cs(vararg ops: Op) = ChangeSet(UUID.randomUUID(), "test", null, Instant.EPOCH, "t", ops.toList())

    @Test
    fun `opening a database starts the channel, a remote submit reaches it and updates the dataset`() {
        val ctrl = controller()
        val db = fixture()
        ctrl.openDatabase(db)

        val result = AgentChannelClient.trySubmit(db, cs(Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Вклад", "del" to false))))
        result.shouldBeInstanceOf<SubmitResult.Applied>()

        val dataset = (ctrl.state.value as AppState.Ready).dataset
        dataset.accounts.any { it.name == "Вклад" } shouldBe true
    }

    @Test
    fun `a remote submit sets the pending flag MainWindow polls, consuming it clears it`() {
        val ctrl = controller()
        ctrl.openDatabase(fixture())
        ctrl.consumeRemoteChange() shouldBe false // nothing yet

        val result = ctrl.applyRemoteChangeSet(cs(Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Вклад", "del" to false))))
        result.shouldBeInstanceOf<SubmitResult.Applied>()

        ctrl.consumeRemoteChange() shouldBe true
        ctrl.consumeRemoteChange() shouldBe false // consumed, not sticky
    }

    @Test
    fun `closeDatabase stops the channel, a remote submit then comes back null`() {
        val ctrl = controller()
        val db = fixture()
        ctrl.openDatabase(db)
        ctrl.closeDatabase()

        AgentChannelClient.trySubmit(db, cs()) shouldBe null
    }

    @Test
    fun `a second instance opening the same database read-only does not start its own channel`() {
        val ctrl1 = controller()
        val db = fixture()
        ctrl1.openDatabase(db)

        val ctrl2 = AppController(AppSettings(tempDir.resolve("config2")))
        val openResult = ctrl2.openDatabase(db)
        (openResult as OpenResult.Success).readOnly shouldBe true

        // Only ctrl1's channel exists; a client talking to it still reaches ctrl1, not ctrl2.
        val result = AgentChannelClient.trySubmit(db, cs(Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Вклад", "del" to false))))
        result.shouldBeInstanceOf<SubmitResult.Applied>()
        (ctrl1.state.value as AppState.Ready).dataset.accounts.any { it.name == "Вклад" } shouldBe true
        (ctrl2.state.value as AppState.Ready).dataset.accounts.any { it.name == "Вклад" } shouldBe false

        ctrl1.closeDatabase()
        ctrl2.closeDatabase()
    }

    @Test
    fun `applyRemoteChangeSet with no database open is a clean error`() {
        val ctrl = controller()
        ctrl.applyRemoteChangeSet(cs()).shouldBeInstanceOf<SubmitResult.Failed>()
    }

    @Test
    fun `the socket file exists only while the database is open`() {
        val ctrl = controller()
        val db = fixture()
        File(AgentChannel.socketPath(db).toString()).exists() shouldBe false
        ctrl.openDatabase(db)
        File(AgentChannel.socketPath(db).toString()).exists() shouldBe true
        ctrl.closeDatabase()
        File(AgentChannel.socketPath(db).toString()).exists() shouldBe false
    }
}
