// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(ExperimentalMcpApi::class)

package kalita.app.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kalita.agent.api.AgentSession
import kalita.agent.api.WriteLimits
import kalita.agent.api.WriteMode
import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.AtomicCommit
import kalita.storage.mdb.CommitResult
import kalita.storage.mdb.createXCashSchemaFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Drives [buildMcpServer]'s real [Server] in-process through the MCP SDK's own [Client], connected
 * via [ChannelTransport.createLinkedPair] — no stdio, no subprocess, matching the spirit of
 * [CliTest]'s black-box-via-public-API testing (calibrated to the same depth, not more).
 */
class McpServerTest {
    private fun fixture(dir: Path): File {
        val file = dir.resolve("a.mdb").toFile()
        createXCashSchemaFixture(file)
        val ops = listOf(
            Op.Insert(Table.Accounts, TempKey("card"), mapOf("Name" to "Карта", "del" to false)),
            Op.Insert(Table.Currencies, TempKey("rub"), mapOf("Name" to "RUB", "del" to false)),
            Op.Insert(Table.BudgetItems, TempKey("food"), mapOf("Name" to "Еда", "del" to false)),
            Op.Insert(Table.BudgetItems, TempKey("cafe"), mapOf("Name" to "Кафе", "Parent_ID" to TempKey("food"), "del" to false)),
            Op.Insert(Table.OperationTypes, TempKey("expense"), mapOf("Name" to "Расход", "PlusMinus" to -1L, "del" to false)),
            Op.Insert(Table.OperationTypes, TempKey("income"), mapOf("Name" to "Доход", "PlusMinus" to 1L, "del" to false)),
        )
        val result = AtomicCommit.commit(file, listOf(ChangeSet(UUID.randomUUID(), "fixture", null, Instant.EPOCH, "fixture", ops)))
        check(result is CommitResult.Committed) { "fixture setup failed: $result" }
        return file
    }

    private suspend fun connectedClient(server: Server): Client {
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        server.createSession(serverTransport)
        val client = Client(Implementation(name = "test-client", version = "1.0.0"))
        client.connect(clientTransport)
        return client
    }

    private fun CallToolResult.text(): String = (content.single() as TextContent).text
    private fun CallToolResult.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    @Test
    fun `tools list is gated by write mode`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val readOnlyClient = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))
                readOnlyClient.listTools().tools.map { it.name }.toSet() shouldBe
                    setOf("get_context", "resolve", "list_operations", "get_balances", "list_inbox")

                val writeClient = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                writeClient.listTools().tools.map { it.name }.toSet() shouldBe setOf(
                    "get_context", "resolve", "list_operations", "get_balances", "list_inbox",
                    "add_operations", "approve_inbox", "reject_inbox",
                    "update_operation", "delete_operation", "restore_operation", "add_dictionary_item",
                )
            }
        }
    }

    @Test
    fun `get_context round-trips today and a dictionary name`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val client = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))
                val result = client.callTool("get_context", emptyMap())
                result.isError shouldBe false
                result.text() shouldContain "\"today\""
                result.text() shouldContain "Карта"
            }
        }
    }

    @Test
    fun `add_operations with dryRun previews without writing, in write mode`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val dataset = session.dataset
                val draft = mapOf(
                    "date" to "2026-01-05",
                    "amount" to "350",
                    "currencyId" to dataset.currencies.single { it.name == "RUB" }.id,
                    "budgetItemId" to dataset.budgetItems.single { it.name == "Кафе" }.id,
                    "typeId" to dataset.operationTypes.single { it.name == "Расход" }.id,
                    "accountFrom" to dataset.accounts.single { it.name == "Карта" }.id,
                )

                val client = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                val result = client.callTool(
                    "add_operations",
                    mapOf("drafts" to listOf(draft), "dryRun" to true, "clientRequestId" to UUID.randomUUID().toString()),
                )
                result.isError shouldBe false
                result.json().getValue("dryRun").jsonPrimitive.boolean shouldBe true

                val listed = client.callTool("list_operations", emptyMap())
                listed.json().getValue("totalCount").jsonPrimitive.int shouldBe 0
            }
        }
    }

    @Test
    fun `add_operations in draft mode lands in inbox, then approve_inbox commits it`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val dataset = session.dataset
                val draft = mapOf(
                    "date" to "2026-01-05",
                    "amount" to "350",
                    "currencyId" to dataset.currencies.single { it.name == "RUB" }.id,
                    "budgetItemId" to dataset.budgetItems.single { it.name == "Кафе" }.id,
                    "typeId" to dataset.operationTypes.single { it.name == "Расход" }.id,
                    "accountFrom" to dataset.accounts.single { it.name == "Карта" }.id,
                )

                val draftClient = connectedClient(buildMcpServer(session, WriteMode.Draft, WriteLimits(), "agent:test"))
                val added = draftClient.callTool(
                    "add_operations",
                    mapOf("drafts" to listOf(draft), "clientRequestId" to UUID.randomUUID().toString()),
                )
                added.isError shouldBe false

                val readClient = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))
                readClient.callTool("list_operations", emptyMap()).json().getValue("totalCount").jsonPrimitive.int shouldBe 0
                val inboxId = readClient.callTool("list_inbox", emptyMap())
                    .json().getValue("entries").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content

                val writeClient = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                writeClient.callTool("approve_inbox", mapOf("inboxId" to inboxId)).isError shouldBe false

                readClient.callTool("list_operations", emptyMap()).json().getValue("totalCount").jsonPrimitive.int shouldBe 1
            }
        }
    }

    @Test
    fun `update_operation, delete_operation and restore_operation round-trip through write mode`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val dataset = session.dataset
                val draft = mapOf(
                    "date" to "2026-01-05",
                    "amount" to "350",
                    "currencyId" to dataset.currencies.single { it.name == "RUB" }.id,
                    "budgetItemId" to dataset.budgetItems.single { it.name == "Кафе" }.id,
                    "typeId" to dataset.operationTypes.single { it.name == "Расход" }.id,
                    "accountFrom" to dataset.accounts.single { it.name == "Карта" }.id,
                )
                val client = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                client.callTool("add_operations", mapOf("drafts" to listOf(draft), "clientRequestId" to UUID.randomUUID().toString()))
                val id = client.callTool("list_operations", emptyMap())
                    .json().getValue("rows").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content

                val updated = client.callTool(
                    "update_operation",
                    mapOf("id" to id.toLong(), "draft" to (draft + ("comment" to "changed")), "clientRequestId" to UUID.randomUUID().toString()),
                )
                updated.isError shouldBe false
                client.callTool("list_operations", emptyMap()).json()
                    .getValue("rows").jsonArray.single().jsonObject.getValue("comment").jsonPrimitive.content shouldBe "changed"

                val deleted = client.callTool("delete_operation", mapOf("id" to id.toLong(), "clientRequestId" to UUID.randomUUID().toString()))
                deleted.isError shouldBe false
                client.callTool("list_operations", emptyMap()).json().getValue("totalCount").jsonPrimitive.int shouldBe 0

                val restored = client.callTool("restore_operation", mapOf("id" to id.toLong(), "clientRequestId" to UUID.randomUUID().toString()))
                restored.isError shouldBe false
                client.callTool("list_operations", emptyMap()).json().getValue("totalCount").jsonPrimitive.int shouldBe 1
            }
        }
    }

    @Test
    fun `add_dictionary_item adds a new account, by explicit call only`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val client = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                val result = client.callTool(
                    "add_dictionary_item",
                    mapOf("kind" to "account", "name" to "Вклад", "clientRequestId" to UUID.randomUUID().toString()),
                )
                result.isError shouldBe false
                result.json().getValue("kind").jsonPrimitive.content shouldBe "Account"
            }
        }
    }

    @Test
    fun `add_operations with a draft missing required fields returns isError true`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val client = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                val result = client.callTool(
                    "add_operations",
                    mapOf(
                        "drafts" to listOf(mapOf("amount" to "350")),
                        "clientRequestId" to UUID.randomUUID().toString(),
                    ),
                )
                result.isError shouldBe true
            }
        }
    }
}
