// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.ChangeSet
import kalita.storage.api.FieldChange
import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Model-based check of `AtomicCommit` + `MdbEngine`: a random script of user actions is applied to a
 * real Jet file and to a plain in-memory model; after every action the stored data must equal the
 * model and the file must pass `MdbVerifier` (index walk, unique ids, no dangling references).
 */
class ChangeSetPropertyTest {
    sealed interface Action {
        data class AddAccount(val name: String) : Action
        data class AddOperation(val cents: Long, val comment: String, val account: Int, val day: Int) : Action
        data class AddOperationWithNewAccount(val cents: Long, val account: String) : Action
        data class RenameAccount(val index: Int, val name: String) : Action
        data class ToggleAccountDeleted(val index: Int) : Action
        data class EditSumma(val index: Int, val cents: Long) : Action
    }

    private data class Acc(val id: Long, var name: String, var del: Boolean)
    private data class Oper(val id: Long, var cents: Long, val comment: String, val account: Long, val date: LocalDateTime)

    @Provide
    fun scripts(): Arbitrary<List<Action>> {
        val text = Arbitraries.strings().withCharRange('a', 'я').ofMinLength(1).ofMaxLength(12)
        val cents = Arbitraries.longs().between(-5_000_000L, 5_000_000L)
        val idx = Arbitraries.integers().between(0, 1000)
        val action: Arbitrary<Action> = Arbitraries.oneOf(
            text.map { Action.AddAccount(it) },
            cents.flatMap { c ->
                text.flatMap { t ->
                    idx.flatMap { a -> Arbitraries.integers().between(1, 28).map { d -> Action.AddOperation(c, t, a, d) } }
                }
            },
            cents.flatMap { c -> text.map { Action.AddOperationWithNewAccount(c, it) } },
            idx.flatMap { i -> text.map { Action.RenameAccount(i, it) } },
            idx.map { Action.ToggleAccountDeleted(it) },
            idx.flatMap { i -> cents.map { Action.EditSumma(i, it) } },
        )
        return action.list().ofMaxSize(10)
    }

    private fun cs(description: String, vararg ops: Op) =
        ChangeSet(UUID.randomUUID(), "prop", null, Instant.EPOCH, description, ops.toList())

    private fun money(cents: Long) = BigDecimal.valueOf(cents, 2)

    @Property(tries = 25)
    fun `database always equals the model and stays consistent`(@ForAll("scripts") script: List<Action>) {
        val dir = Files.createTempDirectory("kalita-prop")
        try {
            val file = createXCashSchemaFixture(File(dir.toFile(), "Data.mdb"))
            val accounts = mutableListOf<Acc>()
            val operations = mutableListOf<Oper>()

            fun commit(change: ChangeSet): Map<TempKey, Long> =
                (AtomicCommit.commit(file, listOf(change)).also { it.shouldBeInstanceOf<CommitResult.Committed>() } as CommitResult.Committed).tempKeys

            for (action in script) {
                when (action) {
                    is Action.AddAccount -> {
                        val ids = commit(cs("acc", Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to action.name, "del" to false))))
                        accounts += Acc(ids.getValue(TempKey("a")), action.name, false)
                    }
                    is Action.AddOperation -> {
                        if (accounts.isEmpty()) continue
                        val acc = accounts[action.account % accounts.size]
                        val date = LocalDateTime.of(2026, 10, action.day, 12, 0, 0)
                        val ids = commit(cs("op", Op.Insert(Table.Operations, TempKey("o"), mapOf(
                            "Date" to date, "Summa" to money(action.cents), "Comment" to action.comment,
                            "AccountFrom" to acc.id, "del" to false))))
                        operations += Oper(ids.getValue(TempKey("o")), action.cents, action.comment, acc.id, date)
                    }
                    is Action.AddOperationWithNewAccount -> {
                        val date = LocalDateTime.of(2026, 10, 1, 8, 0, 0)
                        val ids = commit(cs("both",
                            Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to action.account, "del" to false)),
                            Op.Insert(Table.Operations, TempKey("o"), mapOf(
                                "Date" to date, "Summa" to money(action.cents), "Comment" to "", "AccountFrom" to TempKey("a"), "del" to false))))
                        accounts += Acc(ids.getValue(TempKey("a")), action.account, false)
                        operations += Oper(ids.getValue(TempKey("o")), action.cents, "", ids.getValue(TempKey("a")), date)
                    }
                    is Action.RenameAccount -> {
                        if (accounts.isEmpty()) continue
                        val acc = accounts[action.index % accounts.size]
                        commit(cs("rename", Op.Update(Table.Accounts, RowRef.Existing(acc.id), mapOf("Name" to FieldChange(acc.name, action.name)))))
                        acc.name = action.name
                    }
                    is Action.ToggleAccountDeleted -> {
                        if (accounts.isEmpty()) continue
                        val acc = accounts[action.index % accounts.size]
                        commit(cs("del", Op.SetDeleted(Table.Accounts, RowRef.Existing(acc.id), acc.del, !acc.del)))
                        acc.del = !acc.del
                    }
                    is Action.EditSumma -> {
                        if (operations.isEmpty()) continue
                        val op = operations[action.index % operations.size]
                        commit(cs("edit", Op.Update(Table.Operations, RowRef.Existing(op.id), mapOf("Summa" to FieldChange(money(op.cents), money(action.cents))))))
                        op.cents = action.cents
                    }
                }

                val snapshot = MdbSnapshotReader.read(file)
                snapshot.rows(Table.Accounts).map { Triple((it["id"] as Long), it["Name"], it["del"]) } shouldBe
                    accounts.map { Triple(it.id, it.name, it.del) }
                snapshot.rows(Table.Operations).map {
                    listOf(it["id"], (it["Summa"] as BigDecimal).movePointRight(2).longValueExact(), it["Comment"] ?: "", it["AccountFrom"], it["Date"])
                } shouldBe operations.map { listOf(it.id, it.cents, it.comment, it.account, it.date) }
                MdbVerifier.verify(file) shouldBe VerifyReport(emptyList(), emptyList())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Property(tries = 25)
    fun `a change set with a stale base value is rejected and leaves the file byte-identical`(
        @ForAll("names") actual: String,
        @ForAll("names") stale: String,
        @ForAll("names") wanted: String,
    ) {
        if (stale == actual) return
        val dir = Files.createTempDirectory("kalita-prop")
        try {
            val file = createXCashSchemaFixture(File(dir.toFile(), "Data.mdb"))
            val id = (AtomicCommit.commit(file, listOf(cs("a", Op.Insert(Table.Accounts, TempKey("a"), mapOf("Name" to actual, "del" to false))))) as CommitResult.Committed)
                .tempKeys.getValue(TempKey("a"))
            val before = file.readBytes()

            val result = AtomicCommit.commit(file, listOf(cs("stale", Op.Update(Table.Accounts, RowRef.Existing(id), mapOf("Name" to FieldChange(stale, wanted))))))

            result.shouldBeInstanceOf<CommitResult.Conflicts>()
            file.readBytes().contentEquals(before) shouldBe true
            File(dir.toFile(), "Data.mdb.kalita-tmp").exists() shouldBe false
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Provide
    fun names(): Arbitrary<String> = Arbitraries.strings().withCharRange('a', 'я').ofMinLength(1).ofMaxLength(12)
}
