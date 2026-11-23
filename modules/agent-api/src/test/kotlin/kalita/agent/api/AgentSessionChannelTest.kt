// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.DbLocation
import kalita.storage.api.SubmitResult
import kalita.storage.api.SyncStatus
import kalita.storage.api.Table
import kalita.storage.local.AgentChannelServer
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * [AgentSession.submit] forwarding over [kalita.storage.local.AgentChannelClient] (dev_plan.md
 * §12.15, FR-AG6). A regular [LocalStorageProvider.open] session holds the write lock and stands
 * in for the desktop app without requiring a second process.
 */
class AgentSessionChannelTest {
    @Test
    fun `an existing agent forwards writes while the desktop is open and writes locally after it closes`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)
        val dataDirectory = defaultDataDirectory(file)

        AgentSession.open(file).use { agentSession ->
            LocalStorageProvider.open(DbLocation.Local(file, dataDirectory)).use { desktopSession ->
                desktopSession.status shouldBe SyncStatus.Local
                val channel = AgentChannelServer.start(file) { changeSet -> desktopSession.submit(changeSet) }
                checkNotNull(channel).use {
                    val outcome = agentSession.submit("Агент (test): добавлен счёт", "agent:test") { builder ->
                        builder.insert(Table.Accounts, builder.nextTempKey(), mapOf("Name" to "Вклад"))
                    }
                    outcome.result.shouldBeInstanceOf<SubmitResult.Applied>()
                    desktopSession.snapshot.rows(Table.Accounts).map { it["Name"] } shouldBe listOf("Карта", "Наличные", "Вклад")
                }
            }

            agentSession.refresh()
            val outcome = agentSession.submit("Агент (test): добавлен счёт", "agent:test") { builder ->
                builder.insert(Table.Accounts, builder.nextTempKey(), mapOf("Name" to "Сбережения"))
            }
            outcome.result.shouldBeInstanceOf<SubmitResult.Applied>()
        }
    }

    @Test
    fun `a standalone agent releases its write lock after submit`(@TempDir dir: Path) {
        val file = dir.resolve("a.mdb").toFile()
        buildDemoFixture(file)

        AgentSession.open(file).use { agentSession ->
            agentSession.readOnly shouldBe false
            val outcome = agentSession.submit("Агент (test): добавлен счёт", "agent:test") { builder ->
                builder.insert(Table.Accounts, builder.nextTempKey(), mapOf("Name" to "Вклад"))
            }
            outcome.result.shouldBeInstanceOf<SubmitResult.Applied>()

            LocalStorageProvider.open(DbLocation.Local(file, agentSession.dataDirectory)).use { desktopSession ->
                desktopSession.status shouldBe SyncStatus.Local
                desktopSession.snapshot.rows(Table.Accounts).map { it["Name"] } shouldBe listOf("Карта", "Наличные", "Вклад")
            }
        }
    }
}
