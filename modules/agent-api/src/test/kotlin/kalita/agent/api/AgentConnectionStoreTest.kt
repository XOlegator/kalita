// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class AgentConnectionStoreTest {
    @Test
    fun `an empty store lists nothing`(@TempDir dir: Path) {
        AgentConnectionStore(dir.toFile()).list() shouldBe emptyList()
    }

    @Test
    fun `upsert adds a connection, visible to a fresh store instance on the same directory`(@TempDir dir: Path) {
        AgentConnectionStore(dir.toFile()).upsert(AgentConnection("Claude Desktop", WriteMode.Draft))
        AgentConnectionStore(dir.toFile()).find("Claude Desktop") shouldBe AgentConnection("Claude Desktop", WriteMode.Draft)
    }

    @Test
    fun `upsert with an existing name replaces it, not duplicates it`(@TempDir dir: Path) {
        val store = AgentConnectionStore(dir.toFile())
        store.upsert(AgentConnection("Claude Code", WriteMode.Draft))
        store.upsert(AgentConnection("Claude Code", WriteMode.Write, WriteLimits(BigDecimal("500"), BigDecimal("2000"))))

        store.list() shouldBe listOf(AgentConnection("Claude Code", WriteMode.Write, WriteLimits(BigDecimal("500"), BigDecimal("2000"))))
    }

    @Test
    fun `limits round-trip through the file, including unset ones`(@TempDir dir: Path) {
        val store = AgentConnectionStore(dir.toFile())
        store.upsert(AgentConnection("A", WriteMode.Write, WriteLimits(BigDecimal("100.50"), null)))
        store.upsert(AgentConnection("B", WriteMode.ReadOnly))

        AgentConnectionStore(dir.toFile()).find("A")?.limits shouldBe WriteLimits(BigDecimal("100.50"), null)
        AgentConnectionStore(dir.toFile()).find("B")?.limits shouldBe WriteLimits()
    }

    @Test
    fun `remove drops a connection by name, leaving the others`(@TempDir dir: Path) {
        val store = AgentConnectionStore(dir.toFile())
        store.upsert(AgentConnection("A", WriteMode.Draft))
        store.upsert(AgentConnection("B", WriteMode.Write))
        store.remove("A")

        store.list().map { it.name } shouldBe listOf("B")
    }

    @Test
    fun `find on an unknown name is null`(@TempDir dir: Path) {
        AgentConnectionStore(dir.toFile()).find("nope") shouldBe null
    }
}
