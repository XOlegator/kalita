// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.devcli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.AtomicCommit
import kalita.storage.mdb.createXCashSchemaFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class CliTest {
    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Result {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(o), PrintStream(e))
        return Result(code, o.toString(), e.toString())
    }

    private fun addRow(file: File, table: Table, name: String, ops: List<Op> = emptyList()) {
        val cs = ChangeSet(UUID.randomUUID(), "t", null, Instant.EPOCH, "t",
            listOf(Op.Insert(table, TempKey("k"), mapOf("Name" to name, "del" to false))) + ops)
        AtomicCommit.commit(file, listOf(cs))
    }

    @Test
    fun `dump lists tables and rows`(@TempDir dir: Path) {
        val f = createXCashSchemaFixture(dir.resolve("a.mdb").toFile())
        addRow(f, Table.Accounts, "Cash")
        val r = cli("dump", f.path)
        r.code shouldBe 0
        r.out shouldContain "== Account (1 rows)"
        r.out shouldContain "Name=Cash"
    }

    @Test
    fun `verify passes a clean database and warns about dangling references`(@TempDir dir: Path) {
        val f = createXCashSchemaFixture(dir.resolve("a.mdb").toFile())
        cli("verify", f.path).let { it.code shouldBe 0; it.out shouldContain "OK (0 warnings)" }
        AtomicCommit.commit(f, listOf(ChangeSet(UUID.randomUUID(), "t", null, Instant.EPOCH, "t",
            listOf(Op.Insert(Table.Operations, TempKey("o"), mapOf("Currency_ID" to 77L, "del" to false))))))
        cli("verify", f.path).let { it.code shouldBe 0; it.out shouldContain "WARN  DvizhenieDeneg#1.Currency_ID -> missing Currency#77" }
    }

    @Test
    fun `verify fails on a foreign schema`(@TempDir dir: Path) {
        val f = dir.resolve("a.mdb").toFile()
        io.github.spannm.jackcess.DatabaseBuilder.create(io.github.spannm.jackcess.Database.FileFormat.V2000, f).close()
        cli("verify", f.path).let { it.code shouldBe 1; it.out shouldContain "ERROR schema: missing table" }
    }

    @Test
    fun `diff reports added rows and exits 1, identical exits 0`(@TempDir dir: Path) {
        val a = createXCashSchemaFixture(dir.resolve("a.mdb").toFile())
        val b = dir.resolve("b.mdb").toFile().also { a.copyTo(it) }
        cli("diff", a.path, b.path).let { it.code shouldBe 0; it.out shouldContain "identical" }
        addRow(b, Table.People, "Ann")
        cli("diff", a.path, b.path).let { it.code shouldBe 1; it.out shouldContain "+ People#1"; it.out shouldContain "1 differences" }
    }

    @Test
    fun `bad usage and missing files exit 2`() {
        cli("bogus").code shouldBe 2
        cli("dump", "/nonexistent.mdb").let { it.code shouldBe 2; it.err shouldContain "no such file" }
    }
}
