// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import kalita.agent.api.AgentConnection
import kalita.agent.api.WriteMode
import kalita.storage.mdb.createXCashSchemaFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** [AppController.agentConnectionsStore] (dev_plan.md §12.14/§12.15, FR-AG5), the model behind
 * [AgentConnectionsDialog]. */
class AppControllerAgentConnectionsTest {
    @TempDir
    lateinit var tempDir: Path

    private fun fixture(): File = createXCashSchemaFixture(tempDir.resolve("Data.mdb").toFile())

    private fun controller(): AppController = AppController(AppSettings(tempDir.resolve("config")))

    @Test
    fun `no database open means no store`() {
        controller().agentConnectionsStore() shouldBe null
    }

    @Test
    fun `an open database's store persists a connection across lookups`() {
        val ctrl = controller()
        ctrl.openDatabase(fixture())
        val store = checkNotNull(ctrl.agentConnectionsStore())
        store.upsert(AgentConnection("Claude Desktop", WriteMode.Write))

        checkNotNull(ctrl.agentConnectionsStore()).find("Claude Desktop")?.mode shouldBe WriteMode.Write
    }

    @Test
    fun `closing the database means no store again`() {
        val ctrl = controller()
        ctrl.openDatabase(fixture())
        ctrl.closeDatabase()
        ctrl.agentConnectionsStore() shouldBe null
    }
}
