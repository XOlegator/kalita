// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Drives the `kalita` CLI the same way `modules:cli`'s `CliTest` drives `devcli`: through `run`,
 * capturing stdout/stderr, never `exitProcess`. Calibrated to that test's thoroughness, not more. */
class CliTest {
    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Result {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(o), PrintStream(e))
        return Result(code, o.toString(), e.toString())
    }

    /** A schema-only fixture plus a Card account, a RUB currency, Кафе under Еда, and Доход/Расход types. */
    private fun fixture(dir: Path): String {
        val file = dir.resolve("a.mdb").toFile()
        createXCashSchemaFixture(file)
        val ops = listOf(
            Op.Insert(Table.Accounts, TempKey("card"), mapOf("Name" to "Карта", "del" to false)),
            Op.Insert(Table.Currencies, TempKey("rub"), mapOf("Name" to "RUB", "del" to false)),
            Op.Insert(Table.BudgetItems, TempKey("food"), mapOf("Name" to "Еда", "del" to false)),
            Op.Insert(Table.BudgetItems, TempKey("cafe"), mapOf("Name" to "Кафе", "Parent_ID" to TempKey("food"), "del" to false)),
            Op.Insert(Table.OperationTypes, TempKey("expense"), mapOf("Name" to "Расход", "PlusMinus" to -1L, "del" to false)),
            Op.Insert(Table.OperationTypes, TempKey("income"), mapOf("Name" to "Доход", "PlusMinus" to 1L, "del" to false)),
        )
        val result = AtomicCommit.commit(file, listOf(ChangeSet(UUID.randomUUID(), "fixture", null, Instant.EPOCH, "fixture", ops)))
        check(result is CommitResult.Committed) { "fixture setup failed: $result" }
        return file.path
    }

    @Test
    fun `context --json reports today and dictionaries`(@TempDir dir: Path) {
        val db = fixture(dir)
        val r = cli("context", "--json", "--db", db)
        r.code shouldBe 0
        r.out shouldContain "\"today\""
        r.out shouldContain "Карта"
    }

    @Test
    fun `op add --dry-run previews without writing`(@TempDir dir: Path) {
        val db = fixture(dir)
        val r = cli(
            "op", "add", "--db", db, "--type", "expense", "--amount", "350", "--category", "Кафе",
            "--account", "Карта", "--date", "2026-01-05", "--dry-run", "--mode", "write",
            "--request-id", UUID.randomUUID().toString(), "--json",
        )
        r.code shouldBe 0
        r.out shouldContain "\"dryRun\": true"
        cli("op", "list", "--db", db, "--json").out shouldContain "\"totalCount\": 0"
    }

    @Test
    fun `op add in draft mode shows in inbox, then approve commits it`(@TempDir dir: Path) {
        val db = fixture(dir)
        val add = cli(
            "op", "add", "--db", db, "--type", "expense", "--amount", "350", "--category", "Кафе",
            "--account", "Карта", "--date", "2026-01-05", "--mode", "draft",
            "--request-id", UUID.randomUUID().toString(), "--json",
        )
        add.code shouldBe 0

        val listed = cli("inbox", "list", "--db", db, "--json")
        listed.code shouldBe 0
        listed.out shouldContain "\"id\""
        val inboxId = Regex("\"id\": \"([-0-9a-f]+)\"").find(listed.out)!!.groupValues[1]

        cli("op", "list", "--db", db, "--json").out shouldContain "\"totalCount\": 0"

        val approved = cli("inbox", "approve", inboxId, "--db", db, "--json")
        approved.code shouldBe 0

        cli("inbox", "list", "--db", db, "--json").out shouldContain "\"entries\": []"
        cli("op", "list", "--db", db, "--json").out shouldContain "\"totalCount\": 1"
    }

    @Test
    fun `op add in write mode commits directly`(@TempDir dir: Path) {
        val db = fixture(dir)
        val r = cli(
            "op", "add", "--db", db, "--type", "income", "--amount", "1000", "--category", "Еда",
            "--account", "Карта", "--date", "2026-01-05", "--mode", "write",
            "--request-id", UUID.randomUUID().toString(), "--json",
        )
        r.code shouldBe 0
        cli("op", "list", "--db", db, "--json").out shouldContain "\"totalCount\": 1"
        cli("balance", "--db", db, "--json").out shouldContain "1000.0000"
    }

    @Test
    fun `op update changes an existing operation, op delete and op restore toggle it`(@TempDir dir: Path) {
        val db = fixture(dir)
        cli(
            "op", "add", "--db", db, "--type", "expense", "--amount", "350", "--category", "Кафе",
            "--account", "Карта", "--date", "2026-01-05", "--mode", "write",
            "--request-id", UUID.randomUUID().toString(), "--json",
        ).code shouldBe 0
        val listed = cli("op", "list", "--db", db, "--json")
        val id = Regex("\"id\": (\\d+)").find(listed.out)!!.groupValues[1]

        val updated = cli(
            "op", "update", id, "--db", db, "--type", "expense", "--amount", "500", "--category", "Кафе",
            "--account", "Карта", "--date", "2026-01-05", "--comment", "changed",
            "--request-id", UUID.randomUUID().toString(), "--json",
        )
        updated.code shouldBe 0
        cli("op", "list", "--db", db, "--json").out shouldContain "changed"

        val deleted = cli("op", "delete", id, "--db", db, "--request-id", UUID.randomUUID().toString(), "--json")
        deleted.code shouldBe 0
        cli("op", "list", "--db", db, "--json").out shouldContain "\"totalCount\": 0"

        val restored = cli("op", "restore", id, "--db", db, "--request-id", UUID.randomUUID().toString(), "--json")
        restored.code shouldBe 0
        cli("op", "list", "--db", db, "--json").out shouldContain "\"totalCount\": 1"
    }

    @Test
    fun `undo reverses an op add by changeSetId, scoped to the device that made it`(@TempDir dir: Path) {
        val db = fixture(dir)
        val added = cli(
            "op", "add", "--db", db, "--type", "expense", "--amount", "350", "--category", "Кафе",
            "--account", "Карта", "--date", "2026-01-05", "--mode", "write",
            "--request-id", UUID.randomUUID().toString(), "--json",
        )
        added.code shouldBe 0
        val changeSetId = Regex("\"changeSetId\": \"([-0-9a-f]+)\"").find(added.out)!!.groupValues[1]
        cli("op", "list", "--db", db, "--json").out shouldContain "\"totalCount\": 1"

        val undone = cli("undo", changeSetId, "--db", db, "--request-id", UUID.randomUUID().toString(), "--json")
        undone.code shouldBe 0
        cli("op", "list", "--db", db, "--json").out shouldContain "\"totalCount\": 0"
    }

    @Test
    fun `undo with an unknown changeSetId exits non-zero`(@TempDir dir: Path) {
        val db = fixture(dir)
        val r = cli("undo", UUID.randomUUID().toString(), "--db", db, "--request-id", UUID.randomUUID().toString(), "--json")
        r.code shouldBe 1
    }

    @Test
    fun `dict add creates a new account`(@TempDir dir: Path) {
        val db = fixture(dir)
        val r = cli(
            "dict", "add", "--db", db, "--kind", "account", "--name", "Вклад",
            "--request-id", UUID.randomUUID().toString(), "--json",
        )
        r.code shouldBe 0
        r.out shouldContain "\"kind\": \"Account\""
    }

    @Test
    fun `agent add creates a connection, agent list shows it, agent remove drops it`(@TempDir dir: Path) {
        val db = fixture(dir)
        val added = cli("agent", "add", "--db", db, "--name", "Claude Desktop", "--mode", "write", "--max-amount", "500", "--json")
        added.code shouldBe 0
        added.out shouldContain "\"name\": \"Claude Desktop\""
        added.out shouldContain "\"mode\": \"Write\""

        val listed = cli("agent", "list", "--db", db, "--json")
        listed.code shouldBe 0
        listed.out shouldContain "Claude Desktop"

        val removed = cli("agent", "remove", "--db", db, "--name", "Claude Desktop", "--json")
        removed.code shouldBe 0
        cli("agent", "list", "--db", db, "--json").out shouldContain "\"connections\": []"
    }

    @Test
    fun `agent add with an existing name replaces it, not duplicates it`(@TempDir dir: Path) {
        val db = fixture(dir)
        cli("agent", "add", "--db", db, "--name", "A", "--mode", "draft", "--json")
        cli("agent", "add", "--db", db, "--name", "A", "--mode", "write", "--json")

        val listed = cli("agent", "list", "--db", db, "--json")
        Regex("\"name\"").findAll(listed.out).count() shouldBe 1
        listed.out shouldContain "\"mode\": \"Write\""
    }

    @Test
    fun `mcp with an unknown --connection exits 2 before touching stdio`(@TempDir dir: Path) {
        val db = fixture(dir)
        val r = cli("mcp", "--db", db, "--connection", "nope")
        r.code shouldBe 2
        r.err shouldContain "no such agent connection"
    }

    @Test
    fun `an ambiguous category name is reported and exits non-zero`(@TempDir dir: Path) {
        val dbPath = fixture(dir)
        // Add a second account also named "Карта" so --account "Карта" becomes ambiguous.
        val ops = listOf(Op.Insert(Table.Accounts, TempKey("card2"), mapOf("Name" to "Карта", "del" to false)))
        AtomicCommit.commit(java.io.File(dbPath), listOf(ChangeSet(UUID.randomUUID(), "fixture", null, Instant.EPOCH, "fixture", ops)))

        val r = cli(
            "op", "add", "--db", dbPath, "--type", "expense", "--amount", "350", "--category", "Кафе",
            "--account", "Карта", "--date", "2026-01-05", "--mode", "draft",
            "--request-id", UUID.randomUUID().toString(), "--json",
        )
        r.code shouldBe 1
        r.out shouldContain "ambiguous_or_not_found"
    }

    @Test
    fun `a nonexistent database exits 2`() {
        cli("context", "--db", "/no/such/file.mdb", "--json").code shouldBe 2
    }
}
