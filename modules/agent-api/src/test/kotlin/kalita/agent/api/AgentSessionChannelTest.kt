// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.DbLocation
import kalita.storage.api.SubmitResult
import kalita.storage.api.Table
import kalita.storage.local.AgentChannelServer
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * [AgentSession.submit] forwarding over [kalita.storage.local.AgentChannelClient] (dev_plan.md
 * §12.15, FR-AG6). [LocalStorageProvider.open] on the same data directory a second time in one
 * process is exactly what [kalita.storage.local.DatabaseLock] treats as "already open elsewhere" —
 * the same situation a real second `kalita` process would hit against a running desktop instance —
 * so it stands in for "the desktop app" here without a second process.
 */
class AgentSessionChannelTest {
    @Test
    fun `a second AgentSession forwards its write through the channel instead of failing read-only`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        val dataDirectory = defaultDataDirectory(file)

        // Stands in for AppController: the one session that actually holds the write lock.
        val desktopSession = LocalStorageProvider.open(DbLocation.Local(file, dataDirectory))
        val channel = AgentChannelServer.start(file) { changeSet -> desktopSession.submit(changeSet) }
        checkNotNull(channel).use {
            AgentSession.open(file).use { agentSession ->
                agentSession.readOnly shouldBe true // confirms the lock really is held elsewhere

                val result = agentSession.submit("Агент (test): добавлен счёт", "agent:test") { builder ->
                    builder.insert(Table.Accounts, builder.nextTempKey(), mapOf("Name" to "Вклад"))
                }
                result.shouldBeInstanceOf<SubmitResult.Applied>()
            }
        }

        desktopSession.snapshot.rows(Table.Accounts).map { it["Name"] } shouldBe listOf("Карта", "Наличные", "Вклад")
        desktopSession.close()
    }

    @Test
    fun `with no desktop instance running, submit falls back to the local session as before`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)

        AgentSession.open(file).use { agentSession ->
            agentSession.readOnly shouldBe false
            val result = agentSession.submit("Агент (test): добавлен счёт", "agent:test") { builder ->
                builder.insert(Table.Accounts, builder.nextTempKey(), mapOf("Name" to "Вклад"))
            }
            result.shouldBeInstanceOf<SubmitResult.Applied>()
        }
    }
}
