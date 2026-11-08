// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.ChangeSet
import kalita.storage.api.FieldChange
import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.CommitResult
import kalita.storage.mdb.MdbSnapshotReader
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

private fun cs(device: String = "test", vararg ops: Op) = ChangeSet(UUID.randomUUID(), device, null, Instant.EPOCH, "test", ops.toList())

// The core scenario this срез exists to prove (dev_plan.md §10.3's "муж и жена"), automated: two
// independent NextcloudDatabase instances (simulating two devices) against one FakeNextcloudServer.
class NextcloudDatabaseTest {
    private lateinit var server: FakeNextcloudServer
    private val remotePath = "Documents/private/Data.mdb"

    @BeforeEach
    fun setUp() {
        server = FakeNextcloudServer()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun seedServer(tempDir: Path): String {
        val fixture = createDemoFixture(tempDir.resolve("seed.mdb").toFile(), operationCount = 1)
        return server.seedFile(remotePath, fixture.readBytes())
    }

    private fun openDevice(name: String, tempDir: Path) =
        NextcloudDatabase.open(server.baseUrl, server.loginName, "app-password", remotePath, tempDir.resolve(name).toFile())

    @Test
    fun `opening a fresh location downloads the server's current copy`(@TempDir tempDir: Path) {
        val etag = seedServer(tempDir)
        val expected = server.currentEtag(remotePath)
        etag shouldBe expected

        val db = openDevice("device", tempDir)

        db.file.isFile shouldBe true
        db.queueSize shouldBe 0
        db.close()
    }

    @Test
    fun `submit applies locally and queues without touching the network`(@TempDir tempDir: Path) {
        seedServer(tempDir)
        val db = openDevice("device", tempDir)
        server.close() // the server is gone; submit must not need it

        val result = db.submit(cs(ops = arrayOf(Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to "Новый счёт", "del" to false)))))

        result.shouldBeInstanceOf<CommitResult.Committed>()
        db.queueSize shouldBe 1
        MdbSnapshotReader.read(db.file).rows(Table.Accounts).any { it["Name"] == "Новый счёт" } shouldBe true
        db.close()
    }

    @Test
    fun `two devices' non-conflicting changes both survive and end up on both copies`(@TempDir tempDir: Path) {
        seedServer(tempDir)
        val husband = openDevice("husband", tempDir)
        val wife = openDevice("wife", tempDir)

        husband.submit(cs("husband", Op.Insert(Table.Accounts, TempKey("h"), mapOf("Name" to "Шиномонтаж", "del" to false))))
        wife.submit(cs("wife", Op.Insert(Table.People, TempKey("w"), mapOf("Name" to "Аптека", "del" to false))))

        // Wife syncs first (empty server queue on her side rebases trivially), then the husband —
        // his push has to rebase onto what she already landed.
        wife.trySync() shouldBe SyncOutcome.Pushed
        husband.trySync() shouldBe SyncOutcome.Pushed

        // The wife hasn't pulled the husband's push yet; a second sync with nothing of her own
        // queued just pulls it (§10.3's "Бензин 2000" appearing on the wife's screen).
        wife.trySync() shouldBe SyncOutcome.PulledOnly

        val husbandFinal = MdbSnapshotReader.read(husband.file)
        val wifeFinal = MdbSnapshotReader.read(wife.file)
        husband.file.readBytes().contentEquals(wife.file.readBytes()) shouldBe true
        husbandFinal.rows(Table.Accounts).any { it["Name"] == "Шиномонтаж" } shouldBe true
        wifeFinal.rows(Table.People).any { it["Name"] == "Аптека" } shouldBe true
        husbandFinal.rows(Table.People).any { it["Name"] == "Аптека" } shouldBe true
        wifeFinal.rows(Table.Accounts).any { it["Name"] == "Шиномонтаж" } shouldBe true

        husband.close()
        wife.close()
    }

    @Test
    fun `conflicting edits to the same field of the same row surface as Conflicted, not silently merged`(@TempDir tempDir: Path) {
        seedServer(tempDir)
        val husband = openDevice("husband", tempDir)
        val wife = openDevice("wife", tempDir)

        // Both start from the same base value for Account #1's name, each changing it differently.
        husband.submit(cs("husband", Op.Update(Table.Accounts, RowRef.Existing(1), mapOf("Name" to FieldChange("Кошелёк", "Основной счёт")))))
        wife.submit(cs("wife", Op.Update(Table.Accounts, RowRef.Existing(1), mapOf("Name" to FieldChange("Кошелёк", "Общий счёт")))))

        husband.trySync() shouldBe SyncOutcome.Pushed
        val outcome = wife.trySync()

        outcome.shouldBeInstanceOf<SyncOutcome.Conflicted>()
        wife.queueSize shouldBe 1 // the conflicting change stays queued for the user to resolve (§10.5)
        MdbSnapshotReader.read(wife.file).row(Table.Accounts, 1)?.get("Name") shouldBe "Основной счёт" // the husband's write, pulled

        husband.close()
        wife.close()
    }

    @Test
    fun `a second process cannot open the same cache directory at the same time`(@TempDir tempDir: Path) {
        seedServer(tempDir)
        val first = openDevice("device", tempDir)

        val error = runCatching { openDevice("device", tempDir) }.exceptionOrNull()

        error.shouldBeInstanceOf<IllegalStateException>()
        first.close()
    }

    @Test
    fun `moveLocalDatabaseToNextcloud uploads once and refuses a second time`(@TempDir tempDir: Path) {
        val local = createDemoFixture(tempDir.resolve("local.mdb").toFile(), operationCount = 1)
        val location = kalita.storage.api.DbLocation.Nextcloud(
            server.baseUrl, server.loginName, "app-password", remotePath, tempDir.resolve("cache").toFile(),
        )

        val first = NextcloudStorageProvider.moveLocalDatabaseToNextcloud(local, location)
        first.shouldBeInstanceOf<NextcloudStorageProvider.MoveResult.Moved>()
        File(location.dataDirectory, "server-copy.mdb").readBytes().contentEquals(local.readBytes()) shouldBe true

        val second = NextcloudStorageProvider.moveLocalDatabaseToNextcloud(local, location.copy(dataDirectory = tempDir.resolve("cache2").toFile()))
        second.shouldBeInstanceOf<NextcloudStorageProvider.MoveResult.AlreadyExistsOnServer>()
    }
}
