// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kalita.core.OperationDraft
import kalita.storage.api.DbLocation
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import kalita.storage.local.toDataset
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class InboxToolsTest {
    private fun draft(ids: DemoIds) = OperationDraft(
        date = LocalDate.of(2026, 1, 5),
        amount = BigDecimal("350.00"),
        currencyId = ids.currencyRub,
        budgetItemId = ids.budgetCafe,
        typeId = ids.typeExpense,
        accountFrom = ids.accountCard,
        comment = "coffee",
    )

    @Test
    fun `borrowed session commits through existing storage without closing it`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val dataDir = defaultDataDirectory(file)
        LocalStorageProvider.open(DbLocation.Local(file, dataDir)).use { storage ->
            val entry = InboxStore(dataDir).add(draft(ids), "agent:test", "request")
            AgentSession.borrow(storage, dataDir).use { agent ->
                ApproveInboxTool(agent).execute(entry.id).getValue("ok").jsonPrimitive.boolean shouldBe true
            }
            storage.snapshot.toDataset(dataDir).operations.size shouldBe 1
            storage.refresh()
        }
    }

    @Test
    fun `concurrent store instances preserve every entry`(@TempDir dir: Path) {
        val ids = buildDemoFixture(dir.resolve("a.mdb").toFile())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 80).map { index ->
                pool.submit { InboxStore(dir.toFile()).add(draft(ids), "agent:test", index.toString()) }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            InboxStore(dir.toFile()).readAll().size shouldBe 80
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `store waits for another process holding the inbox lock`(@TempDir dir: Path) {
        val ids = buildDemoFixture(dir.resolve("a.mdb").toFile())
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classes = Path.of(InboxLockProcess::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val process = ProcessBuilder(java, "-cp", classes, InboxLockProcess::class.java.name, dir.resolve("inbox.lock").toString()).start()
        val pool = Executors.newSingleThreadExecutor()
        try {
            process.inputStream.bufferedReader().readLine() shouldBe "locked"
            val task = pool.submit { InboxStore(dir.toFile()).add(draft(ids), "agent:test", "request") }
            assertThrows<TimeoutException> { task.get(300, TimeUnit.MILLISECONDS) }
            process.outputStream.write(1)
            process.outputStream.flush()
            task.get(30, TimeUnit.SECONDS)
            process.waitFor(30, TimeUnit.SECONDS) shouldBe true
            process.exitValue() shouldBe 0
            InboxStore(dir.toFile()).readAll().size shouldBe 1
        } finally {
            process.destroyForcibly()
            pool.shutdownNow()
        }
    }

    @Test
    fun `uncertain approval blocks retries and requires explicit recovery`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val store = InboxStore(session.dataDirectory)
            val entry = store.add(draft(ids), "agent:test", "request")
            store.locked { updateStatus(entry.id, INBOX_STATUS_APPLYING) }
            val recovery = InboxRecovery(session)
            recovery.list().getValue("entries").jsonArray.size shouldBe 1
            ApproveInboxTool(session).execute(entry.id).getValue("ok").jsonPrimitive.boolean shouldBe false
            RejectInboxTool(session).execute(entry.id).getValue("ok").jsonPrimitive.boolean shouldBe false
            session.dataset.operations.size shouldBe 0
            recovery.resolve(entry.id, false).getValue("ok").jsonPrimitive.boolean shouldBe true
            ApproveInboxTool(session).execute(entry.id).getValue("ok").jsonPrimitive.boolean shouldBe true
            recovery.resolve(entry.id, false).getValue("ok").jsonPrimitive.boolean shouldBe false
            session.dataset.operations.size shouldBe 1

            val other = store.add(draft(ids), "agent:test", "other")
            store.locked { updateStatus(other.id, INBOX_STATUS_APPLYING) }
            recovery.resolve(other.id, true).getValue("ok").jsonPrimitive.boolean shouldBe true
            ApproveInboxTool(session).execute(other.id).getValue("ok").jsonPrimitive.boolean shouldBe false
            recovery.list().getValue("entries").jsonArray.shouldBeEmpty()
        }
    }

    @Test
    fun `simultaneous approvals submit only once`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val entry = InboxStore(session.dataDirectory).add(draft(ids), "agent:test", "request")
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results = (0 until 2).map {
                    pool.submit<Boolean> { ApproveInboxTool(session).execute(entry.id).getValue("ok").jsonPrimitive.boolean }
                }.map { it.get(30, TimeUnit.SECONDS) }
                results.count { it } shouldBe 1
                session.dataset.operations.size shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `failure after commit stays blocked across sessions`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        val id = AgentSession.open(file).use { session ->
            val entry = InboxStore(session.dataDirectory).add(draft(ids), "agent:test", "request")
            assertThrows<IllegalStateException> {
                ApproveInboxTool(session).executeWithHook(entry.id) { error("simulated crash") }
            }
            session.dataset.operations.size shouldBe 1
            entry.id
        }
        AgentSession.open(file).use { session ->
            ApproveInboxTool(session).execute(id).getValue("ok").jsonPrimitive.boolean shouldBe false
            session.dataset.operations.size shouldBe 1
            InboxRecovery(session).resolve(id, true).getValue("ok").jsonPrimitive.boolean shouldBe true
            ApproveInboxTool(session).execute(id).getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }

    @Test
    fun `approve commits the inbox entry and removes it from the pending list`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val added = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Draft,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            val inboxId = added.getValue("inbox").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content

            val approved = ApproveInboxTool(session).execute(inboxId)
            approved.getValue("ok").jsonPrimitive.boolean shouldBe true

            ListInboxTool(session).execute().getValue("entries").jsonArray.shouldBeEmpty()
            session.dataset.operations.size shouldBe 1
        }
    }

    @Test
    fun `reject marks the entry rejected without touching the mdb`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val added = AddOperationsTool(session).execute(
                drafts = listOf(draft(ids)),
                mode = WriteMode.Draft,
                clientRequestId = UUID.randomUUID().toString(),
                device = "agent:test",
            )
            val inboxId = added.getValue("inbox").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content

            val rejected = RejectInboxTool(session).execute(inboxId)
            rejected.getValue("ok").jsonPrimitive.boolean shouldBe true

            ListInboxTool(session).execute().getValue("entries").jsonArray.shouldBeEmpty()
            session.dataset.operations.size shouldBe 0

            // A second reject of the same (no longer pending) entry is an error, not a crash.
            RejectInboxTool(session).execute(inboxId).getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }

    @Test
    fun `approving an unknown id is a clean error`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            ApproveInboxTool(session).execute("no-such-id").getValue("ok").jsonPrimitive.boolean shouldBe false
        }
    }
}
