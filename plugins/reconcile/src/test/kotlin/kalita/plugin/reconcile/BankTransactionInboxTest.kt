// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import io.kotest.matchers.shouldBe
import kalita.plugin.api.BankTransaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BankTransactionInboxTest {
    @TempDir
    lateinit var dir: Path

    private val transaction = BankTransaction(
        source = "банк|a", externalId = "1", bankAccount = "счёт", postedAt = LocalDate.parse("2026-11-01"),
        operationAt = LocalDateTime.parse("2026-10-31T23:59:59.123"), amount = BigDecimal("-123.4500"),
        currency = "RSD", originalAmount = BigDecimal("1.2300"), originalCurrency = "EUR",
        description = "Покупка\n\"тест\"", merchant = "Магазин", mcc = "0123", status = BankTransaction.Status.Posted,
    )

    @Test
    fun `concurrent instances preserve additions and removals`() {
        val initial = (0 until 40).map { transaction.copy(externalId = "old-$it") }
        BankTransactionInbox(dir.toFile()).add(initial)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 8).map { worker ->
                executor.submit {
                    val inbox = BankTransactionInbox(dir.toFile())
                    start.await()
                    repeat(10) { index ->
                        inbox.add(listOf(transaction.copy(externalId = "new-$worker-$index")))
                        if (worker < 4) inbox.remove(setOf(initial[worker * 10 + index].inboxKey()))
                        inbox.read()
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val result = BankTransactionInbox(dir.toFile()).read()
        result.map { it.externalId }.toSet() shouldBe
            (0 until 8).flatMap { worker -> (0 until 10).map { "new-$worker-$it" } }.toSet()
    }

    @Test
    fun `operations wait for a lock held by another process`() {
        val inbox = BankTransactionInbox(dir.toFile())
        inbox.add(listOf(transaction))
        val source = dir.resolve("LockHolder.java").toFile()
        source.writeText(
            """
            import java.nio.channels.FileChannel;
            import java.nio.file.Path;
            import static java.nio.file.StandardOpenOption.WRITE;
            public class LockHolder {
                public static void main(String[] args) throws Exception {
                    try (var channel = FileChannel.open(Path.of(args[0]), WRITE); var lock = channel.lock()) {
                        System.out.println("locked");
                        System.out.flush();
                        System.in.read();
                    }
                }
            }
            """.trimIndent(),
        )
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            source.path,
            dir.resolve("bank-inbox.json.lock").toString(),
        ).redirectErrorStream(true).start()
        val executor = Executors.newFixedThreadPool(3)
        try {
            executor.submit<String> { process.inputStream.bufferedReader().readLine() }.get(30, TimeUnit.SECONDS) shouldBe "locked"
            val started = CountDownLatch(3)
            val tasks = listOf<() -> Unit>(
                { inbox.read() },
                { BankTransactionInbox(dir.toFile()).add(listOf(transaction.copy(externalId = "new"))) },
                { BankTransactionInbox(dir.toFile()).remove(setOf(transaction.inboxKey())) },
            ).map { operation -> executor.submit { started.countDown(); operation() } }
            started.await(30, TimeUnit.SECONDS) shouldBe true
            tasks.forEach { task -> assertThrows<TimeoutException> { task.get(200, TimeUnit.MILLISECONDS) } }
            process.outputStream.close()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            process.waitFor(30, TimeUnit.SECONDS) shouldBe true
            process.exitValue() shouldBe 0
            inbox.read().map { it.externalId } shouldBe listOf("new")
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }

    @Test
    fun `round trip preserves every field and nullable values`() {
        val inbox = BankTransactionInbox(dir.toFile())
        inbox.read() shouldBe emptyList()
        val nullable = transaction.copy(externalId = "2", operationAt = null, originalAmount = null, originalCurrency = null, merchant = null, mcc = null)
        inbox.add(listOf(transaction, nullable))
        BankTransactionInbox(dir.toFile()).read() shouldBe listOf(transaction, nullable)
    }

    @Test
    fun `deduplicate by both key components and remove only selected keys`() {
        val inbox = BankTransactionInbox(dir.toFile())
        val other = transaction.copy(source = "банк", externalId = "a|1")
        val third = transaction.copy(source = "other")
        inbox.add(listOf(transaction, transaction.copy(description = "duplicate"), other))
        inbox.add(listOf(transaction, third))
        inbox.read() shouldBe listOf(transaction, other, third)
        inbox.remove(setOf(other.inboxKey(), BankTransactionKey("missing", "key")))
        BankTransactionInbox(dir.toFile()).read() shouldBe listOf(transaction, third)
        inbox.remove(setOf(transaction.inboxKey(), third.inboxKey()))
        inbox.read() shouldBe emptyList()
    }

    @Test
    fun `holds are not queued and can be added when posted`() {
        val inbox = BankTransactionInbox(dir.toFile())
        inbox.add(listOf(transaction.copy(status = BankTransaction.Status.Pending)))
        inbox.read() shouldBe emptyList()
        inbox.add(listOf(transaction.copy(status = BankTransaction.Status.Pending), transaction))
        inbox.read() shouldBe listOf(transaction)
        inbox.add(listOf(transaction.copy(status = BankTransaction.Status.Pending)))
        inbox.read() shouldBe listOf(transaction)
    }

    @Test
    fun `corrupt data fails without being overwritten`() {
        val file = dir.resolve("bank-inbox.json").toFile()
        val inbox = BankTransactionInbox(dir.toFile())
        for (invalid in listOf("", "[", "{}", "[{}]", "[null]")) {
            file.writeText(invalid)
            assertThrows<Exception> { inbox.read() }
            assertThrows<Exception> { inbox.add(listOf(transaction)) }
            assertThrows<Exception> { inbox.remove(setOf(transaction.inboxKey())) }
            file.readText() shouldBe invalid
        }
        file.delete()
        inbox.add(listOf(transaction))
        val valid = file.readText()
        for (invalid in listOf(
            valid.replace("-123.4500", "invalid"),
            valid.replace("\"Posted\"", "\"Pending\""),
            valid.replace("\"Posted\"", "\"unknown\""),
            "[${valid.substring(1, valid.length - 1)},${valid.substring(1, valid.length - 1)}]",
        )) {
            file.writeText(invalid)
            assertThrows<Exception> { inbox.add(listOf(transaction)) }
            file.readText() shouldBe invalid
        }
    }
}