// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest

// Reproduces, against FakeNextcloudServer, exactly what was checked live against a real Nextcloud
// server (dev_plan.md Этап 9 срез 1): stale If-Match -> 412 with the current ETag already in the
// response, GET carries OC-Checksum, If-None-Match: * refuses to clobber an existing file.
class NextcloudClientTest {
    private lateinit var server: FakeNextcloudServer
    private lateinit var client: NextcloudClient

    @BeforeEach
    fun setUp() {
        server = FakeNextcloudServer()
        client = NextcloudClient(server.baseUrl, server.loginName, "app-password")
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `stat returns the current ETag and size, null for a missing file`() {
        val etag = server.seedFile("Documents/private/Data.mdb", "hello".toByteArray())

        val info = client.stat("Documents/private/Data.mdb").shouldNotBeNull()

        info.etag shouldBe etag
        info.size shouldBe 5
        client.stat("Documents/private/missing.mdb") shouldBe null
    }

    @Test
    fun `get returns bytes, ETag and the SHA1 checksum, null for a missing file`() {
        val bytes = "database bytes".toByteArray()
        val etag = server.seedFile("Documents/private/Data.mdb", bytes)

        val file = client.get("Documents/private/Data.mdb").shouldNotBeNull()

        file.bytes shouldBe bytes
        file.etag shouldBe etag
        file.sha1 shouldBe MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        client.get("Documents/private/missing.mdb") shouldBe null
    }

    @Test
    fun `put with the correct If-Match succeeds and returns the new ETag`() {
        val etag = server.seedFile("Documents/private/Data.mdb", "v1".toByteArray())

        val result = client.put("Documents/private/Data.mdb", "v2".toByteArray(), etag)

        result.shouldBeInstanceOf<NextcloudPutResult.Applied>()
        client.get("Documents/private/Data.mdb")!!.bytes shouldBe "v2".toByteArray()
    }

    @Test
    fun `put with a stale If-Match is rejected and leaves the file untouched`() {
        val etag = server.seedFile("Documents/private/Data.mdb", "v1".toByteArray())
        server.seedFile("Documents/private/Data.mdb", "v2 - someone else wrote first".toByteArray()) // moves the ETag on

        val result = client.put("Documents/private/Data.mdb", "v3 - should not land".toByteArray(), etag)

        result.shouldBeInstanceOf<NextcloudPutResult.PreconditionFailed>()
        client.get("Documents/private/Data.mdb")!!.bytes shouldBe "v2 - someone else wrote first".toByteArray()
    }

    @Test
    fun `putIfAbsent succeeds on a fresh path`() {
        val result = client.putIfAbsent("Documents/private/NewDb.mdb", "fresh".toByteArray())

        result.shouldBeInstanceOf<NextcloudPutResult.Applied>()
        client.get("Documents/private/NewDb.mdb")!!.bytes shouldBe "fresh".toByteArray()
    }

    @Test
    fun `putIfAbsent refuses to overwrite a file that already exists`() {
        server.seedFile("Documents/private/Data.mdb", "existing data".toByteArray())

        val result = client.putIfAbsent("Documents/private/Data.mdb", "should not land".toByteArray())

        result.shouldBeInstanceOf<NextcloudPutResult.PreconditionFailed>()
        client.get("Documents/private/Data.mdb")!!.bytes shouldBe "existing data".toByteArray()
    }

    @Test
    fun `list returns direct children only, with folders flagged`() {
        server.seedFolder("Documents/private")
        server.seedFile("Documents/private/Data.mdb", "db".toByteArray())
        server.seedFile("Documents/private/notes.txt", "n".toByteArray())
        server.seedFolder("Documents/private/sub")
        server.seedFile("Documents/private/sub/deep.txt", "d".toByteArray()) // not a direct child

        val entries = client.list("Documents/private")

        entries.map { it.path to it.isFolder }.shouldContainExactlyInAnyOrder(
            "Documents/private/Data.mdb" to false,
            "Documents/private/notes.txt" to false,
            "Documents/private/sub" to true,
        )
    }
}
