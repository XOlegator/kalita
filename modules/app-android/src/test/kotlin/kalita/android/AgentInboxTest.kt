// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import kalita.storage.local.defaultDataDirectory
import kalita.storage.mdb.createDemoFixture
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AgentInboxTest {
    @Test
    fun `approval rejection and recovery use the open session`(@TempDir dir: Path) {
        val privateDir = dir.resolve("private").toFile().apply { mkdirs() }
        val fixture = createDemoFixture(dir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(privateDir)
        fixture.inputStream().use { controller.importAndOpen(it) }
        controller.addCurrency("Рубль", null)
        controller.addBudgetItem(null, "Статья")
        val inbox = defaultDataDirectory(privateDir.resolve("Data.mdb")).resolve("inbox.jsonl")
        inbox.parentFile!!.mkdirs()
        val dataset = (controller.state.value as AndroidAppState.Ready).dataset
        fun entry(id: String, status: String) = """{"id":"$id","createdAt":"2026-01-01T00:00:00Z","device":"agent:test","clientRequestId":"r","status":"$status","draft":{"date":"2026-11-07","amount":"150","typeId":2,"currencyId":${dataset.currencies.first().id},"budgetItemId":${dataset.budgetItems.first().id},"accountFrom":1}}"""
        inbox.writeText(listOf(entry("a", "pending"), entry("b", "pending"), entry("c", "applying")).joinToString("\n"))
        controller.agentInbox().first.getValue("entries").jsonArray.size shouldBe 2
        controller.agentInbox().second.getValue("entries").jsonArray.size shouldBe 1
        val before = (controller.state.value as AndroidAppState.Ready).dataset.operations.size
        val approval = controller.processAgentInbox("a", "approve")
        check(approval.getValue("ok").jsonPrimitive.boolean) { approval.toString() }
        (controller.state.value as AndroidAppState.Ready).dataset.operations.size shouldBe before + 1
        controller.processAgentInbox("a", "approve").getValue("ok").jsonPrimitive.boolean shouldBe false
        controller.processAgentInbox("b", "reject").getValue("ok").jsonPrimitive.boolean shouldBe true
        controller.processAgentInbox("c", "notCommitted").getValue("ok").jsonPrimitive.boolean shouldBe true
        controller.agentInbox().first.getValue("entries").jsonArray.size shouldBe 1
        controller.processAgentInbox("c", "reject").getValue("ok").jsonPrimitive.boolean shouldBe true
        controller.closeDatabase()
    }
}