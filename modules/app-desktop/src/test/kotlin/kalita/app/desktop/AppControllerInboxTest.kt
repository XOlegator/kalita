// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import kalita.agent.api.AddOperationsTool
import kalita.agent.api.AgentSession
import kalita.agent.api.WriteMode
import kalita.core.OperationDraft
import kalita.core.OperationKind
import kalita.platform.NoopSecretStore
import kalita.storage.local.defaultDataDirectory
import kalita.storage.mdb.createDemoFixture
import kalita.sync.NextcloudCredentials
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.LocalDate

class AppControllerInboxTest {
    @Test
    fun `recover interrupted approvals without writing operations`(@TempDir dir: Path) {
        val file = createDemoFixture(dir.resolve("Data.mdb").toFile(), operationCount = 0)
        val ctrl = AppController(AppSettings(dir.resolve("config")))
        ctrl.openDatabase(file)
        try {
            ctrl.addCurrency("RUB", "RUB")
            ctrl.addBudgetItem(null, "Расход")
            val dataset = (ctrl.state.value as AppState.Ready).dataset
            val draft = OperationDraft(
                LocalDate.of(2026, 1, 5), BigDecimal("10.00"), dataset.currencies.first().id,
                dataset.budgetItems.first().id, dataset.operationTypes.first { it.kind == OperationKind.Expense }.id,
                "test", null, dataset.accounts.first().id, null,
            )
            val ids = AgentSession.open(file).use { agent ->
                (1..2).map { n ->
                    AddOperationsTool(agent).execute(listOf(draft), WriteMode.Draft, clientRequestId = "recovery$n", device = "agent:test")
                        .getValue("inbox").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
                }
            }
            val inbox = defaultDataDirectory(file).resolve("inbox.jsonl")
            inbox.writeText(inbox.readText().replace("\"status\":\"pending\"", "\"status\":\"applying\""))
            ctrl.agentInboxRecovery().getValue("entries").jsonArray.size shouldBe 2
            ctrl.resolveAgentInbox(ids[0], true).getValue("ok").jsonPrimitive.boolean shouldBe true
            ctrl.resolveAgentInbox(ids[1], false).getValue("ok").jsonPrimitive.boolean shouldBe true
            ctrl.agentInboxRecovery().getValue("entries").jsonArray.size shouldBe 0
            ctrl.agentInbox().getValue("entries").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content shouldBe ids[1]
            ctrl.resolveAgentInbox(ids[0], false).getValue("ok").jsonPrimitive.boolean shouldBe false
            (ctrl.state.value as AppState.Ready).dataset.operations.size shouldBe 0
        } finally {
            ctrl.closeDatabase()
        }
    }

    @Test
    fun `nextcloud inbox uses cache and submits through open session`(@TempDir dir: Path) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("ETag", "\"1\"")
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        server.start()
        val file = createDemoFixture(dir.resolve("Data.mdb").toFile(), operationCount = 0)
        val ctrl = AppController(AppSettings(dir.resolve("config")), NoopSecretStore)
        try {
            ctrl.openDatabase(file)
            ctrl.addCurrency("RUB", "RUB")
            ctrl.addBudgetItem(null, "Расход")
            val dataset = (ctrl.state.value as AppState.Ready).dataset
            val draft = OperationDraft(
                LocalDate.of(2026, 1, 5), BigDecimal("10.00"), dataset.currencies.first().id,
                dataset.budgetItems.first().id, dataset.operationTypes.first { it.kind == OperationKind.Expense }.id,
                "cloud", null, dataset.accounts.first().id, null,
            )
            val id = AgentSession.open(file).use { agent ->
                AddOperationsTool(agent).execute(listOf(draft), WriteMode.Draft, clientRequestId = "req", device = "agent:test")
                    .getValue("inbox").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
            }
            ctrl.moveToNextcloud(NextcloudCredentials("http://127.0.0.1:${server.address.port}", "user", "password"), "/Data.mdb") shouldBe OpenResult.Success(false)
            defaultDataDirectory(file).resolve("inbox.jsonl").copyTo(checkNotNull(ctrl.databaseContext()).dataDirectory.resolve("inbox.jsonl"))
            ctrl.agentInbox().getValue("entries").jsonArray.size shouldBe 1
            ctrl.approveAgentInbox(id).getValue("ok").jsonPrimitive.boolean shouldBe true
            (ctrl.state.value as AppState.Ready).dataset.operations.size shouldBe 1
            ctrl.agentInbox().getValue("entries").jsonArray.size shouldBe 0
        } finally {
            ctrl.closeDatabase()
            server.stop(0)
        }
    }
    @Test
    fun `approve and reject use current session and update the journal`(@TempDir dir: Path) {
        val file = createDemoFixture(dir.resolve("Data.mdb").toFile(), operationCount = 0)
        val ctrl = AppController(AppSettings(dir.resolve("config")))
        ctrl.openDatabase(file)
        try {
            ctrl.addCurrency("RUB", "RUB")
            ctrl.addBudgetItem(null, "Расход")
            val dataset = (ctrl.state.value as AppState.Ready).dataset
            val draft = OperationDraft(
                LocalDate.of(2026, 1, 5), BigDecimal("10.00"), dataset.currencies.first().id,
                dataset.budgetItems.first().id, dataset.operationTypes.first { it.kind == OperationKind.Expense }.id,
                "test", null, dataset.accounts.first().id, null,
            )
            val ids = AgentSession.open(file).use { agent ->
                (1..2).map { n ->
                    AddOperationsTool(agent).execute(listOf(draft), WriteMode.Draft, clientRequestId = "req$n", device = "agent:test")
                        .getValue("inbox").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
                }
            }
            ctrl.agentInbox().getValue("entries").jsonArray.size shouldBe 2
            ctrl.approveAgentInbox(ids[0]).getValue("ok").jsonPrimitive.boolean shouldBe true
            (ctrl.state.value as AppState.Ready).dataset.operations.size shouldBe 1
            ctrl.approveAgentInbox(ids[0]).getValue("ok").jsonPrimitive.boolean shouldBe false
            ctrl.rejectAgentInbox(ids[1]).getValue("ok").jsonPrimitive.boolean shouldBe true
            ctrl.agentInbox().getValue("entries").jsonArray.size shouldBe 0
            (ctrl.state.value as AppState.Ready).dataset.operations.size shouldBe 1
        } finally {
            ctrl.closeDatabase()
        }
    }
}