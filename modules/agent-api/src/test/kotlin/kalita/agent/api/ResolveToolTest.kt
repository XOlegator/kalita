// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ResolveToolTest {
    @Test
    fun `exact match ranks first with confidence 1_0`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        val ids = buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = ResolveTool(session.dataset).execute("Карта", setOf(ResolveKind.Account))
            val candidates = result.getValue("candidates").jsonArray
            candidates[0].jsonObject.getValue("id").jsonPrimitive.content shouldBe ids.accountCard.toString()
            candidates[0].jsonObject.getValue("confidence").jsonPrimitive.double shouldBe 1.0
        }
    }

    @Test
    fun `substring match ranks below exact and below 1_0`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = ResolveTool(session.dataset).execute("Каф", setOf(ResolveKind.BudgetItem))
            val candidates = result.getValue("candidates").jsonArray
            candidates.size shouldBe 1
            candidates[0].jsonObject.getValue("name").jsonPrimitive.content shouldBe "Кафе"
            (candidates[0].jsonObject.getValue("confidence").jsonPrimitive.double < 1.0) shouldBe true
        }
    }

    @Test
    fun `no match returns an empty candidate list`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = ResolveTool(session.dataset).execute("зюзюзю", setOf(ResolveKind.Account))
            result.getValue("candidates").jsonArray.shouldBeEmpty()
        }
    }

    @Test
    fun `searches across several kinds at once`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        AgentSession.open(file).use { session ->
            val result = ResolveTool(session.dataset).execute("Иван", setOf(ResolveKind.Counterparty, ResolveKind.Account))
            val candidates = result.getValue("candidates").jsonArray
            candidates.size shouldBe 1
            candidates[0].jsonObject.getValue("kind").jsonPrimitive.content shouldBe "Counterparty"
        }
    }
}
