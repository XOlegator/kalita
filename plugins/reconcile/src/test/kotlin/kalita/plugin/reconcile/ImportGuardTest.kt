// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import io.kotest.matchers.shouldBe
import kalita.storage.api.TempKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ImportGuardTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `unfinished import blocks another instance until explicitly resolved`() {
        val first = ImportGuard(dir.toFile())
        val second = ImportGuard(dir.toFile())
        first.begin() shouldBe true
        second.begin() shouldBe false
        first.finish()
        second.begin() shouldBe true
        second.finish()
    }

    private val id = UUID.fromString("a6bcddc7-62c0-4be4-8e20-68038713cc11")
    private val at = Instant.parse("2026-11-11T10:00:00Z")
    private val key = TempKey("new-1")

    private fun prepared() = ReconcileWriter.Prepared(
        listOf(ReconLink("bank|fixed", listOf(7), LinkKind.EXACT, Instant.EPOCH, "")),
        listOf("bank|new" to key), 1, 0,
    )

    @Test
    fun `recover requires exact confirmation and resolves all keys`() {
        val first = ImportGuard(dir.toFile())
        first.begin(prepared(), id, at, "desktop") shouldBe true
        val second = ImportGuard(dir.toFile())
        second.recover { error("live import") } shouldBe ImportGuard.Recovery.BUSY
        first.close()
        second.recover { actual -> actual shouldBe id; null } shouldBe ImportGuard.Recovery.UNCONFIRMED
        assertThrows<IllegalArgumentException> { second.recover { throw IllegalArgumentException("history unavailable") } }
        assertThrows<IllegalStateException> { second.recover { emptyMap() } }
        second.recover { mapOf(key to 42L) } shouldBe ImportGuard.Recovery.RECOVERED
        ReconLinkStore(dir.toFile()).readAll() shouldBe listOf(
            ReconLink("bank|fixed", listOf(7), LinkKind.EXACT, at, "desktop"),
            ReconLink("bank|new", listOf(42), LinkKind.CREATED, at, "desktop"),
        )
        second.recover { error("no marker") } shouldBe ImportGuard.Recovery.NO_MARKER
    }

    @Test
    fun `existing identical links are not duplicated and conflicts retain marker`() {
        val guard = ImportGuard(dir.toFile())
        val store = ReconLinkStore(dir.toFile())
        val fixed = ReconLink("bank|fixed", listOf(7), LinkKind.EXACT, at, "desktop")
        store.append(listOf(fixed, ReconLink("bank|new", listOf(99), LinkKind.CREATED, at, "desktop")))
        guard.begin(prepared(), id, at, "desktop") shouldBe true
        guard.close()
        assertThrows<IllegalStateException> { guard.recover { mapOf(key to 42L) } }
        store.readAll().size shouldBe 2
        dir.resolve("bank-import-in-progress").toFile().isFile shouldBe true
        store.append(listOf(ReconLink("bank|new", listOf(42), LinkKind.CREATED, at, "desktop")))
        guard.recover { mapOf(key to 42L) } shouldBe ImportGuard.Recovery.RECOVERED
        store.readAll().size shouldBe 3
    }

    @Test
    fun `link only recovers without history and malformed markers fail closed`() {
        val guard = ImportGuard(dir.toFile())
        val links = ReconcileWriter.Prepared(listOf(ReconLink("bank|ignored", emptyList(), LinkKind.IGNORED, Instant.EPOCH, "")), emptyList(), 0, 0)
        assertThrows<IllegalArgumentException> { guard.begin(links, id, at, "phone") }
        assertThrows<IllegalArgumentException> { guard.begin(prepared(), null, at, "phone") }
        guard.begin(links, null, at, "phone") shouldBe true
        guard.close()
        guard.recover { error("link only") } shouldBe ImportGuard.Recovery.RECOVERED
        val marker = dir.resolve("bank-import-in-progress").toFile()
        for (text in listOf("", "{broken", "{}")) {
            marker.writeText(text)
            assertThrows<Exception> { guard.recover { emptyMap() } }
            marker.readText() shouldBe text
            guard.begin() shouldBe false
        }
    }

    @Test
    fun `lock excludes another process`() {
        val guard = ImportGuard(dir.toFile())
        guard.begin(prepared(), id, at, "desktop") shouldBe true
        val source = dir.resolve("LockProbe.java").toFile()
        source.writeText(
            """
            import java.nio.channels.FileChannel;
            import java.nio.file.Path;
            import static java.nio.file.StandardOpenOption.WRITE;
            public class LockProbe {
                public static void main(String[] args) throws Exception {
                    try (var channel = FileChannel.open(Path.of(args[0]), WRITE); var lock = channel.tryLock()) {
                        System.out.println(lock == null ? "busy" : "free");
                    }
                }
            }
            """.trimIndent(),
        )
        fun probe(): String {
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, source.path, dir.resolve("bank-import.lock").toString()).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor() shouldBe 0
            return output
        }
        probe() shouldBe "busy"
        ImportGuard(dir.toFile()).recover { error("live import") } shouldBe ImportGuard.Recovery.BUSY
        probe() shouldBe "busy"
        guard.close()
        probe() shouldBe "free"
    }
}