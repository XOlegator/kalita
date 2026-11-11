// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.backup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.plugin.api.ActionOutcome
import kalita.plugin.api.DatabaseContext
import kalita.plugin.api.PluginHost
import kalita.plugin.api.PluginHttpClient
import kalita.plugin.api.PluginLogLevel
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ServiceLoader

private class TestHost(var db: DatabaseContext?, instant: Instant = Instant.parse("2026-11-11T20:05:01Z")) : PluginHost {
    val log = mutableListOf<Pair<PluginLogLevel, String>>()
    override val httpClient = object : PluginHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>) = error("no network")
        override suspend fun post(url: String, body: String, headers: Map<String, String>) = error("no network")
    }
    override val secretStore = object : PluginSecretStore {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }
    override val logger = PluginLogger { l, m -> log += l to m }
    override val clock: Clock = Clock.fixed(instant, ZoneId.of("UTC"))
    override fun database() = db
}

class BackupPluginTest {
    @TempDir
    lateinit var dir: Path

    private fun database(): DatabaseContext {
        val file = dir.resolve("Data.mdb").toFile().apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        return DatabaseContext("Data.mdb", file, dir.resolve("Data.kalita-data").toFile())
    }

    @Test
    fun `is registered as a built-in plugin`() {
        ServiceLoader.load(kalita.plugin.api.Plugin::class.java).any { it is BackupPlugin } shouldBe true
    }

    @Test
    fun `backup now copies the file next to the database with a timestamp suffix`() = runTest {
        val db = database()
        val host = TestHost(db)
        val plugin = BackupPlugin()

        val outcome = plugin.perform(BackupPlugin.ACTION_BACKUP_NOW, host)

        outcome.shouldBeInstanceOf<ActionOutcome.Done>()
        val copy = dir.resolve("Data-20261111-200501.mdb").toFile()
        copy.readBytes() shouldBe db.file.readBytes()
        outcome.message!! shouldContain copy.name
    }

    @Test
    fun `without an open database backup now fails politely`() = runTest {
        BackupPlugin().perform(BackupPlugin.ACTION_BACKUP_NOW, TestHost(null)) shouldBe ActionOutcome.Failed("Нет открытой базы")
        BackupPlugin().perform("nope", TestHost(null)).shouldBeInstanceOf<ActionOutcome.Failed>()
    }

    @Test
    fun `before close backs up only when the toggle is on, into the configured folder`() = runTest {
        val db = database()
        val host = TestHost(db)
        val plugin = BackupPlugin()
        val target = dir.resolve("my-backups").toFile()

        plugin.initialize(host, mapOf(BackupPlugin.ON_CLOSE to false, BackupPlugin.DIRECTORY to target.path))
        plugin.beforeClose(db, host) shouldBe ActionOutcome.Done()
        target.exists() shouldBe false

        plugin.initialize(host, mapOf(BackupPlugin.ON_CLOSE to true, BackupPlugin.DIRECTORY to target.path, BackupPlugin.KEEP to 5.0))
        plugin.beforeClose(db, host).shouldBeInstanceOf<ActionOutcome.Done>()
        target.list()!!.toList() shouldBe listOf("Data-20261111-200501.mdb")
    }

    @Test
    fun `two copies within one second get distinct names`() {
        val db = database()
        val at = LocalDateTime.of(2026, 11, 11, 20, 5, 1)
        BackupPlugin.copyNow(db.file, dir.toFile(), at).name shouldBe "Data-20261111-200501.mdb"
        BackupPlugin.copyNow(db.file, dir.toFile(), at).name shouldBe "Data-20261111-200501-1.mdb"
    }

    @Test
    fun `prune keeps the newest N copies of this database only`() {
        val folder = dir.resolve("b").toFile().apply { mkdirs() }
        val names = listOf(
            "Data-20260101-000000.mdb", "Data-20260102-000000.mdb", "Data-20260102-000000-1.mdb",
            "Data-20260103-000000.mdb", "Other-20260101-000000.mdb", "Data-notes.txt", "Data-20260104-000000.mdb",
        )
        names.forEach { File(folder, it).writeText("x") }

        val removed = BackupPlugin.prune(folder, "Data", 2)

        removed.map { it.name } shouldBe listOf("Data-20260101-000000.mdb", "Data-20260102-000000.mdb", "Data-20260102-000000-1.mdb")
        folder.list()!!.sorted() shouldBe listOf("Data-20260103-000000.mdb", "Data-20260104-000000.mdb", "Data-notes.txt", "Other-20260101-000000.mdb")
    }

    @Test
    fun `a folder that cannot be written is reported, not thrown`() = runTest {
        val db = database()
        val host = TestHost(db)
        val plugin = BackupPlugin()
        val blocker = dir.resolve("file-not-folder").toFile().apply { writeText("x") }
        plugin.initialize(host, mapOf(BackupPlugin.ON_CLOSE to true, BackupPlugin.DIRECTORY to blocker.path))

        val outcome = plugin.beforeClose(db, host)

        outcome.shouldBeInstanceOf<ActionOutcome.Failed>()
        outcome.message shouldContain blocker.path
        host.log.last().first shouldBe PluginLogLevel.Error
    }
}
