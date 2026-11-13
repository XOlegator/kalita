// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(ExperimentalMcpApi::class)

package kalita.app.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kalita.agent.api.AgentSession
import kalita.agent.api.ReceiptProposalStore
import kalita.agent.api.ReceiptReviewModel
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
import kotlinx.serialization.json.long
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

    /** Like [connectedClient], but declaring the `elicitation` capability and answering every
     * `elicitation/create` request with [onElicit] — for exercising [confirmAddOperations]. */
    private suspend fun connectedClientWithElicitation(server: Server, onElicit: (ElicitRequest) -> ElicitResult): Client {
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        server.createSession(serverTransport)
        val client = Client(
            Implementation(name = "test-client", version = "1.0.0"),
            ClientOptions(capabilities = ClientCapabilities(elicitation = ClientCapabilities.Elicitation())),
        )
        client.setElicitationHandler(onElicit)
        client.connect(clientTransport)
        return client
    }

    private fun CallToolResult.text(): String = (content.single() as TextContent).text
    private fun CallToolResult.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    private suspend fun readResourceText(client: Client, uri: String): String {
        val result = client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
        return (result.contents.single() as TextResourceContents).text
    }

    private suspend fun readResourceJson(client: Client, uri: String): JsonObject = Json.parseToJsonElement(readResourceText(client, uri)).jsonObject

    @Test
    fun `tools list is gated by write mode`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val readOnlyClient = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))
                readOnlyClient.listTools().tools.map { it.name }.toSet() shouldBe
                    setOf("get_context", "resolve", "list_operations", "get_balances", "get_spending_summary", "list_inbox",
                        "get_receipt_hints", "get_receipt_proposal")

                val writeClient = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                writeClient.listTools().tools.map { it.name }.toSet() shouldBe setOf(
                    "get_context", "resolve", "list_operations", "get_balances", "get_spending_summary", "list_inbox",
                    "add_operations", "approve_inbox", "reject_inbox", "get_receipt_hints", "get_receipt_proposal", "propose_receipt",
                    "update_operation", "delete_operation", "restore_operation", "add_dictionary_item",
                    "undo_changes",
                )
            }
        }
    }

    @Test
    fun `get_spending_summary groups by budget item over an explicit date range`(@TempDir dir: Path) {
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
                val writeClient = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                writeClient.callTool("add_operations", mapOf("drafts" to listOf(draft), "clientRequestId" to UUID.randomUUID().toString()))

                val readClient = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))
                val result = readClient.callTool(
                    "get_spending_summary",
                    mapOf("dateFrom" to "2026-01-01", "dateTo" to "2026-01-31"),
                )
                result.isError shouldBe false
                val json = result.json()
                json.getValue("byBudgetItem").jsonArray.single().jsonObject.getValue("budgetItem").jsonPrimitive.content shouldBe "Кафе"
                json.getValue("byBudgetItem").jsonArray.single().jsonObject.getValue("expense").jsonPrimitive.content shouldBe "-350.0000"
                json.getValue("totals").jsonObject.getValue("expense").jsonPrimitive.content shouldBe "-350.0000"
                json.getValue("transferCount").jsonPrimitive.int shouldBe 0
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
    fun `add_operations in write mode asks for elicitation confirmation, and a decline writes nothing`(@TempDir dir: Path) {
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
                var seenMessage: String? = null
                val client = connectedClientWithElicitation(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test")) { request ->
                    seenMessage = request.message
                    ElicitResult(ElicitResult.Action.Decline, content = null)
                }

                val result = client.callTool(
                    "add_operations",
                    mapOf("drafts" to listOf(draft), "clientRequestId" to UUID.randomUUID().toString()),
                )
                result.isError shouldBe true
                result.json().getValue("error").jsonPrimitive.content shouldBe "declined_by_user"
                seenMessage shouldContain "350"
                client.callTool("list_operations", emptyMap()).json().getValue("totalCount").jsonPrimitive.int shouldBe 0
            }
        }
    }

    @Test
    fun `add_operations in write mode writes after an elicitation accept`(@TempDir dir: Path) {
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
                val client = connectedClientWithElicitation(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test")) {
                    ElicitResult(ElicitResult.Action.Accept, JsonObject(emptyMap()))
                }

                val result = client.callTool(
                    "add_operations",
                    mapOf("drafts" to listOf(draft), "clientRequestId" to UUID.randomUUID().toString()),
                )
                result.isError shouldBe false
                client.callTool("list_operations", emptyMap()).json().getValue("totalCount").jsonPrimitive.int shouldBe 1
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
    fun `undo_changes reverses add_operations, scoped to the device that made it`(@TempDir dir: Path) {
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
                val added = client.callTool(
                    "add_operations",
                    mapOf("drafts" to listOf(draft), "clientRequestId" to UUID.randomUUID().toString()),
                )
                val changeSetId = added.json().getValue("changeSetId").jsonPrimitive.content
                client.callTool("list_operations", emptyMap()).json().getValue("totalCount").jsonPrimitive.int shouldBe 1

                val undone = client.callTool(
                    "undo_changes",
                    mapOf("changeSetId" to changeSetId, "clientRequestId" to UUID.randomUUID().toString()),
                )
                undone.isError shouldBe false
                client.callTool("list_operations", emptyMap()).json().getValue("totalCount").jsonPrimitive.int shouldBe 0

                val otherDeviceClient = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:other"))
                val secondAdded = client.callTool(
                    "add_operations",
                    mapOf("drafts" to listOf(draft), "clientRequestId" to UUID.randomUUID().toString()),
                )
                val secondChangeSetId = secondAdded.json().getValue("changeSetId").jsonPrimitive.content
                otherDeviceClient.callTool(
                    "undo_changes",
                    mapOf("changeSetId" to secondChangeSetId, "clientRequestId" to UUID.randomUUID().toString()),
                ).isError shouldBe true
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

    @Test
    fun `resources and resource templates are listed, and context resource round-trips a dictionary name`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val client = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))

                client.listResources().resources.map { it.uri }.toSet() shouldBe setOf("kalita://context", "kalita://operations")
                client.listResourceTemplates(ListResourceTemplatesRequest()).resourceTemplates.map { it.uriTemplate } shouldBe
                    listOf("kalita://dictionaries/{name}")

                val context = readResourceText(client, "kalita://context")
                context shouldContain "\"today\""
                context shouldContain "Карта"
            }
        }
    }

    @Test
    fun `dictionaries resource template returns one named dictionary and rejects an unknown one`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val client = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))

                readResourceText(client, "kalita://dictionaries/accounts") shouldContain "Карта"
                readResourceText(client, "kalita://dictionaries/budget-items") shouldContain "Кафе"

                shouldThrow<Exception> { readResourceText(client, "kalita://dictionaries/unknown-kind") }
            }
        }
    }

    @Test
    fun `operations resource mirrors list_operations called with no arguments`(@TempDir dir: Path) {
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
                val writeClient = connectedClient(buildMcpServer(session, WriteMode.Write, WriteLimits(), "agent:test"))
                writeClient.callTool("add_operations", mapOf("drafts" to listOf(draft), "clientRequestId" to UUID.randomUUID().toString()))

                val readClient = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))
                val resourceResult = readResourceJson(readClient, "kalita://operations")
                val toolResult = readClient.callTool("list_operations", emptyMap()).json()
                resourceResult shouldBe toolResult
            }
        }
    }

    @Test
    fun `prompts are listed, and spending_on interpolates its argument into the message`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val client = connectedClient(buildMcpServer(session, WriteMode.ReadOnly, WriteLimits(), "agent:test"))

                client.listPrompts().prompts.map { it.name }.toSet() shouldBe
                    setOf("log_daily_spending", "process_receipt", "monthly_summary", "spending_on")

                val result = client.getPrompt(GetPromptRequest(GetPromptRequestParams(name = "spending_on", arguments = mapOf("category" to "Кафе"))))
                (result.messages.single().content as TextContent).text shouldContain "Кафе"

                shouldThrow<Exception> {
                    client.getPrompt(GetPromptRequest(GetPromptRequestParams(name = "spending_on", arguments = emptyMap())))
                }
            }
        }
    }

    @Test
    fun `propose_receipt shows nothing in the journal and get_receipt_proposal reports the decision and hints learn from it`(@TempDir dir: Path) {
        runBlocking {
            val file = fixture(dir)
            AgentSession.open(file).use { session ->
                val dataset = session.dataset
                val food = dataset.budgetItems.single { it.name == "Еда" }.id
                val cafe = dataset.budgetItems.single { it.name == "Кафе" }.id
                val client = connectedClient(buildMcpServer(session, WriteMode.Draft, WriteLimits(), "agent:test"))

                val proposed = client.callTool(
                    "propose_receipt",
                    mapOf(
                        "merchant" to "Кофейня", "date" to "2026-01-05", "total" to "350.00",
                        "currencyId" to dataset.currencies.single().id, "accountFromId" to dataset.accounts.single().id,
                        "lines" to listOf(mapOf("text" to "Капучино 0,3", "amount" to "350.00", "budgetItemId" to food)),
                        "clientRequestId" to "r-1",
                    ),
                )
                proposed.isError shouldBe false
                val id = proposed.json().getValue("proposalId").jsonPrimitive.content
                session.dataset.operations.size shouldBe 0
                client.callTool("get_receipt_proposal", mapOf("proposalId" to id)).json().getValue("status").jsonPrimitive.content shouldBe "pending"

                // What the program's window does on «Сохранить»: the person moved the line to «Кафе».
                val store = ReceiptProposalStore(session.dataDirectory)
                val proposal = store.find(id)!!
                val model = ReceiptReviewModel(proposal, session.dataset)
                model.rows.single().budgetItemId = cafe
                store.complete(proposal, model.resolution("cs", 1), session.dataset)

                val decided = client.callTool("get_receipt_proposal", mapOf("proposalId" to id, "waitSeconds" to 1)).json()
                decided.getValue("status").jsonPrimitive.content shouldBe "saved"
                decided.getValue("lines").jsonArray.single().jsonObject.getValue("outcome").jsonPrimitive.content shouldBe "corrected"

                val hints = client.callTool("get_receipt_hints", mapOf("texts" to listOf("Капучино 0,4"), "merchant" to "Кофейня")).json()
                hints.getValue("hints").jsonArray.single().jsonObject.getValue("budgetItemId").jsonPrimitive.long shouldBe cafe
            }
        }
    }
}
