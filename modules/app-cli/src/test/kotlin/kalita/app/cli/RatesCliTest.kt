// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kalita.agent.api.AgentSession
import kalita.plugin.api.PluginHttpClient
import kalita.plugin.host.RateLookup
import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.AtomicCommit
import kalita.storage.mdb.CommitResult
import kalita.storage.mdb.createXCashSchemaFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `kalita rates load` end to end against a real file, with the built-in Bank of Russia plugin and a canned HTTP client. */
class RatesCliTest {
    private class Result(val code: Int, val out: String, val err: String)

    private val dollar = """<ValCurs ID="R01235"><Record Date="13.11.2026" Id="R01235"><Nominal>1</Nominal><Value>80,5000</Value></Record>""" +
        """<Record Date="14.11.2026" Id="R01235"><Nominal>1</Nominal><Value>81,2500</Value></Record></ValCurs>"""

    private fun cli(http: (String) -> String, vararg args: String): Result {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val client = object : PluginHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): String = http(url)
            override suspend fun post(url: String, body: String, headers: Map<String, String>): String = error("unused")
        }
        val command = RatesCommand().subcommands(
            RatesLoadCommand(PrintStream(o), PrintStream(e), httpClient = { client }, today = { LocalDate.of(2026, 11, 15) }),
        )
        val code = try {
            command.parse(args.toList())
            0
        } catch (r: ProgramResult) {
            r.statusCode
        }
        return Result(code, o.toString(), e.toString())
    }

    private fun fixture(dir: Path, base: String = "Рубль"): String {
        val file = dir.resolve("a.mdb").toFile()
        createXCashSchemaFixture(file)
        val ops = listOf(
            Op.Insert(Table.Currencies, TempKey("rub"), mapOf("Name" to base, "del" to false)),
            Op.Insert(Table.Currencies, TempKey("usd"), mapOf("Name" to "Доллар", "CB_ID" to "R01235", "del" to false)),
        )
        val result = AtomicCommit.commit(file, listOf(ChangeSet(UUID.randomUUID(), "fixture", null, Instant.EPOCH, "fixture", ops)))
        check(result is CommitResult.Committed) { "fixture setup failed: $result" }
        return file.path
    }

    private fun rates(db: String) = AgentSession.open(File(db)).use { s -> s.dataset.rates.map { it.date to it.value } }

    @Test
    fun `loads the rate in force today, then finds nothing new on a second run`(@TempDir dir: Path) {
        val db = fixture(dir)

        val dry = cli({ dollar }, "load", "--db", db, "--dry-run")
        dry.code shouldBe 0
        dry.out shouldContain "новых 1"
        rates(db) shouldHaveSize 0

        val first = cli({ dollar }, "load", "--db", db)
        first.code shouldBe 0
        first.out shouldContain "Доллар: 1 курс(ов), последний 2026-11-14 = 81.25"
        first.out shouldContain "Записано в базу."
        rates(db) shouldBe listOf(LocalDate.of(2026, 11, 14) to 81.25)

        val second = cli({ dollar }, "load", "--db", db, "--from", "2026-11-13", "--to", "2026-11-14")
        second.out shouldContain "новых 1, изменённых 0, без изменений 1"
        rates(db) shouldHaveSize 2
    }

    @Test
    fun `reports a refusal, a network failure and bad arguments without writing`(@TempDir dir: Path) {
        val foreign = fixture(dir, base = "Тугрик")
        cli({ dollar }, "load", "--db", foreign).let {
            it.code shouldBe 1
            it.err shouldContain "в справочнике валют такой нет"
        }
        val db = fixture(dir.resolve("b").also { it.toFile().mkdirs() })
        cli({ throw java.io.IOException("HTTP 503") }, "load", "--db", db).let {
            it.code shouldBe 1
            it.err shouldContain "HTTP 503"
        }
        cli({ dollar }, "load", "--db", db, "--from", "вчера").code shouldBe 2
        cli({ dollar }, "load", "--db", db, "--source", "nope").code shouldBe 2
        cli({ dollar }, "load", "--db", db, "--currency", "Тугрик").code shouldBe 2
        cli({ dollar }, "load", "--db", db, "--currency", "рубль").out shouldContain "новых 0"
        cli({ dollar }, "load", "--db", dir.resolve("missing.mdb").toString()).code shouldBe 2
        rates(db) shouldHaveSize 0
    }

    private fun dict(lookup: NewCurrencyRateLookup?, vararg args: String): Result {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val command = DictCommand().subcommands(DictAddCommand(PrintStream(o), PrintStream(e), lookup), DictSetCodeCommand(PrintStream(o), PrintStream(e)))
        val code = try {
            command.parse(args.toList())
            0
        } catch (r: ProgramResult) {
            r.statusCode
        }
        return Result(code, o.toString(), e.toString())
    }

    @Test
    fun `dict add stores a new currency with its code, the looked-up rate and the stamp of the usual user`(@TempDir dir: Path) {
        val db = fixture(dir)
        val day = LocalDate.of(2026, 11, 14)
        val found: NewCurrencyRateLookup = { _, name, _, _ -> if (name == "Сербский динар") RateLookup.Found(day, BigDecimal("0.772483"), "ЦБ РФ") else RateLookup.Unavailable("источник молчит") }

        val added = dict(found, "add", "--db", db, "--kind", "currency", "--name", "Сербский динар", "--request-id", UUID.randomUUID().toString())
        added.code shouldBe 0
        added.out shouldContain "код RSD"
        added.out shouldContain "курс 0.772483 на 2026-11-14"
        added.out shouldContain "Курс взят из источника «ЦБ РФ»"

        // A failed lookup never blocks the add; an explicit rate needs no lookup at all.
        dict(found, "add", "--db", db, "--kind", "currency", "--name", "Тугрик", "--request-id", UUID.randomUUID().toString()).out shouldContain "источник молчит"
        dict({ _, _, _, _ -> error("no lookup expected") }, "add", "--db", db, "--kind", "currency", "--name", "Крона", "--rate", "8.2", "--request-id", UUID.randomUUID().toString())
            .out shouldContain "курс 8.2"
        dict({ _, _, _, _ -> error("no lookup expected") }, "add", "--db", db, "--kind", "currency", "--name", "Лира", "--no-rate-lookup", "--request-id", UUID.randomUUID().toString())
            .out shouldContain "No exchange rate"

        AgentSession.open(File(db)).use { s ->
            val dinar = s.dataset.currencies.single { it.name == "Сербский динар" }
            dinar.cbId shouldBe "RSD"
            s.dataset.rates.single { it.currencyId == dinar.id }.value shouldBe 0.772483
            s.dataset.currencies.single { it.name == "Тугрик" }.cbId shouldBe null
        }
    }

    @Test
    fun `dict set-code sets an explicit code or the one the name suggests`(@TempDir dir: Path) {
        val db = fixture(dir)
        dict(null, "add", "--db", db, "--kind", "currency", "--name", "Белорусский рубль", "--cb-id", "BYR", "--request-id", UUID.randomUUID().toString()).code shouldBe 0

        dict(null, "set-code", "--db", db, "--currency", "белорусский рубль").out shouldContain "BYR → BYN"
        dict(null, "set-code", "--db", db, "--currency", "Белорусский рубль").out shouldContain "код уже BYN"
        dict(null, "set-code", "--db", db, "--currency", "Доллар", "--cb-id", "USD").out shouldContain "R01235 → USD"
        dict(null, "set-code", "--db", db, "--currency", "Тугрик").code shouldBe 2
        AgentSession.open(File(db)).use { s -> s.dataset.currencies.single { it.name == "Белорусский рубль" }.cbId shouldBe "BYN" }
    }
}
