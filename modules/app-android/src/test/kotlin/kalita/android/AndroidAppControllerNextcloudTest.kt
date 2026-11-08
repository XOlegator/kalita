// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.platform.NoopSecretStore
import kalita.platform.SecretStore
import kalita.storage.mdb.createXCashSchemaFixture
import kalita.sync.NextcloudCredentials
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

/** In-memory stand-in for a real OS secret store — enough to prove [AndroidAppController]'s own
 * plumbing (remember on success, read back on reopen) without touching the Android Keystore
 * [AndroidSecretStore] itself needs, same reasoning as the plain JVM tests elsewhere in this
 * module not needing a real device. */
private class FakeSecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) {
        values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
}

/**
 * [AndroidAppController.openIfExists] reopening a remembered Nextcloud location at "launch" (a
 * fresh [AndroidAppController] instance, same [filesDir]/[SecretStore] — simulating the app
 * process restarting) — the direct fix for FR-S1/FR-S2's "the app password lived in memory only,
 * so a restart asked to log in again."
 */
class AndroidAppControllerNextcloudTest {
    @TempDir
    lateinit var tempDir: Path

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    /** Same minimal single-endpoint fake as the desktop `AppController`'s equivalent test — just
     * enough for `moveToNextcloud`'s one network call (an unconditional `PUT`, `If-None-Match: *`
     * always satisfied since this fake server never has the file already). */
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
    fun `a Nextcloud location survives a restart and reopens without logging in again`() = runBlocking {
        val filesDir = tempDir.resolve("private").toFile().apply { mkdirs() }
        val secretStore = FakeSecretStore()
        val fixture = createXCashSchemaFixture(tempDir.resolve("picked.mdb").toFile())

        val first = AndroidAppController(filesDir, secretStore)
        fixture.inputStream().use { first.importAndOpen(it) }
        val credentials = NextcloudCredentials(startFakeServer(), "user", "app-password")
        first.moveToNextcloud(credentials, "/Data.mdb")
        first.state.value.shouldBeInstanceOf<AndroidAppState.Ready>()
        first.closeDatabase() // releases the cache lock, same as the real process exiting would

        // A fresh instance over the same filesDir/secretStore — simulating a process restart.
        val second = AndroidAppController(filesDir, secretStore)
        second.openIfExists()
        second.state.value.shouldBeInstanceOf<AndroidAppState.Ready>()
    }

    @Test
    fun `without a stored secret, a remembered location is ignored and the local file wins`() = runBlocking {
        val filesDir = tempDir.resolve("private").toFile().apply { mkdirs() }
        val fixture = createXCashSchemaFixture(tempDir.resolve("picked.mdb").toFile())

        val first = AndroidAppController(filesDir, FakeSecretStore())
        fixture.inputStream().use { first.importAndOpen(it) }
        first.moveToNextcloud(NextcloudCredentials(startFakeServer(), "user", "app-password"), "/Data.mdb")

        // A different SecretStore instance (e.g. the real one, cleared, or just never shared) —
        // the remembered location's password isn't there, so this falls back to the local copy
        // importAndOpen left behind, exactly as if SecretStore had never existed.
        val second = AndroidAppController(filesDir, NoopSecretStore)
        second.openIfExists()
        val state = second.state.value
        state.shouldBeInstanceOf<AndroidAppState.Ready>()
        state.readOnly shouldBe false
    }

    @Test
    fun `switching back to a local database clears the remembered Nextcloud location`() = runBlocking {
        val filesDir = tempDir.resolve("private").toFile().apply { mkdirs() }
        val secretStore = FakeSecretStore()
        val fixture = createXCashSchemaFixture(tempDir.resolve("picked.mdb").toFile())

        val first = AndroidAppController(filesDir, secretStore)
        fixture.inputStream().use { first.importAndOpen(it) }
        first.moveToNextcloud(NextcloudCredentials(startFakeServer(), "user", "app-password"), "/Data.mdb")

        // Explicitly picking a new local file again — should take priority from now on, not the
        // Nextcloud location just left behind.
        val freshLocal = createXCashSchemaFixture(tempDir.resolve("picked2.mdb").toFile())
        freshLocal.inputStream().use { first.importAndOpen(it) }

        val second = AndroidAppController(filesDir, secretStore)
        second.openIfExists()
        second.state.value.shouldBeInstanceOf<AndroidAppState.Ready>()
        File(filesDir, "nextcloud-location.json").isFile shouldBe false
    }
}
