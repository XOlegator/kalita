// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import kalita.agent.api.AgentConnection
import kalita.agent.api.WriteMode
import kalita.storage.mdb.createXCashSchemaFixture
import kalita.sync.NextcloudCredentials
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path

/**
 * [AppController.moveToNextcloud]/[AppController.openFromNextcloud] must leave
 * [AppController.agentConnectionsStore] and [AppController.readHistory] pointed at the Nextcloud
 * session's real local cache directory, not re-derived from the remote path — the bug these guard
 * against (`agentConnectionsStore()`'s own `dataDirectory(File(filePath))` resolved to nonsense
 * once `filePath` became a remote WebDAV path, not a real local one).
 */
class AppControllerNextcloudDataDirectoryTest {
    @TempDir
    lateinit var tempDir: Path

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    /** Just enough of a Nextcloud server for `moveToNextcloud`'s one network call — an unconditional
     * `PUT .../Data.mdb` (the file never exists yet on this fake server, so `If-None-Match: *` is
     * always satisfied). The WebDAV protocol itself is already covered by `sync`'s own
     * `FakeNextcloudServer`; this test is about `AppController`'s plumbing, not the protocol. */
    private fun startFakeServer(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("ETag", "\"1\"")
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun `moving a local database to Nextcloud leaves agentConnectionsStore and readHistory pointed at the real cache directory`() {
        val localFile = createXCashSchemaFixture(tempDir.resolve("Data.mdb").toFile())
        val ctrl = AppController(AppSettings(tempDir.resolve("config")))
        ctrl.openDatabase(localFile)

        val credentials = NextcloudCredentials(startFakeServer(), "user", "app-password")
        ctrl.moveToNextcloud(credentials, "/Data.mdb") shouldBe OpenResult.Success(false)

        val store = checkNotNull(ctrl.agentConnectionsStore())
        store.upsert(AgentConnection("Claude Desktop", WriteMode.Write))
        checkNotNull(ctrl.agentConnectionsStore()).find("Claude Desktop")?.mode shouldBe WriteMode.Write

        // No `history.jsonl` exists for a Nextcloud session yet (a separate, later gap) — the fix
        // here is that this resolves to the real cache directory and answers an empty list, not
        // that it throws or reads some unrelated local database's history by accident.
        ctrl.readHistory() shouldBe emptyList()
    }
}
