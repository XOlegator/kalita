// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.core.AuditStamp
import kalita.core.TreeChange
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ChangeSetBuilderTest {
    private val now = LocalDateTime.of(2026, 10, 22, 14, 30, 0)
    private val audit = AuditStamp(userId = 1, timestamp = now)

    private val accountRow: Row = mapOf(
        "id" to 5L, "Name" to "Cash", "del" to false,
        "Creator" to 1L, "DateCreate" to now.minusDays(10),
        "Editor" to 1L, "DateEdit" to now.minusDays(1),
    )

    private val rateRow: Row = mapOf(
        "id" to 201L, "Currency_ID" to 11L, "Date" to now.minusDays(5), "Course" to 28.78,
        "del" to false, "Creator" to 1L, "DateCreate" to now.minusDays(10),
        "Editor" to 1L, "DateEdit" to now.minusDays(5),
    )

    private val budgetRow1: Row = mapOf(
        "id" to 10L, "Parent_ID" to 1L, "Name" to "a", "Ord" to 1L, "del" to false,
        "Editor" to 1L, "DateEdit" to now.minusDays(2),
    )
    private val budgetRow2: Row = mapOf(
        "id" to 11L, "Parent_ID" to 1L, "Name" to "b", "Ord" to 2L, "del" to false,
        "Editor" to 1L, "DateEdit" to now.minusDays(2),
    )

    private val snapshot = DbSnapshot(
        mapOf(
            Table.Accounts to listOf(accountRow),
            Table.Rates to listOf(rateRow),
            Table.BudgetItems to listOf(budgetRow1, budgetRow2),
        ),
    )

    private fun builder() = ChangeSetBuilder(snapshot, audit, "desktop")

    @Nested
    inner class Insert {
        @Test
        fun `adds audit fields to the inserted row`() {
            val tk = TempKey("t1")
            val cs = builder().insert(Table.Accounts, tk, mapOf("Name" to "New")).build("add account")
            cs.ops shouldHaveSize 1
            val insert = cs.ops[0].shouldBeInstanceOf<Op.Insert>()
            insert.table shouldBe Table.Accounts
            insert.tempKey shouldBe tk
            insert.values["Name"] shouldBe "New"
            insert.values["Creator"] shouldBe 1L
            insert.values["DateCreate"] shouldBe now
            insert.values["Editor"] shouldBe 1L
            insert.values["DateEdit"] shouldBe now
        }

        @Test
        fun `null userId writes null audit ids`() {
            val cs = ChangeSetBuilder(snapshot, AuditStamp(null, now), "desktop")
                .insert(Table.Accounts, TempKey("t1"), mapOf("Name" to "X"))
                .build("test")
            val insert = cs.ops[0].shouldBeInstanceOf<Op.Insert>()
            insert.values["Creator"] shouldBe null
            insert.values["Editor"] shouldBe null
        }
    }

    @Nested
    inner class Update {
        @Test
        fun `reads base values from the snapshot and adds audit fields`() {
            val cs = builder().update(Table.Accounts, 5, mapOf("Name" to "Wallet")).build("rename")
            cs.ops shouldHaveSize 1
            val update = cs.ops[0].shouldBeInstanceOf<Op.Update>()
            update.table shouldBe Table.Accounts
            update.row shouldBe RowRef.Existing(5)
            update.fields["Name"] shouldBe FieldChange(base = "Cash", new = "Wallet")
            update.fields["Editor"] shouldBe FieldChange(base = 1L, new = 1L)
            update.fields["DateEdit"]!!.new shouldBe now
        }

        @Test
        fun `missing row throws IllegalArgumentException`() {
            shouldThrow<IllegalArgumentException> {
                builder().update(Table.Accounts, 999, mapOf("Name" to "X"))
            }
        }
    }

    @Nested
    inner class SoftDeleteAndRestore {
        @Test
        fun `softDelete captures current del state`() {
            val cs = builder().softDelete(Table.Accounts, 5).build("delete")
            val op = cs.ops[0].shouldBeInstanceOf<Op.SetDeleted>()
            op.base shouldBe false
            op.new shouldBe true
        }

        @Test
        fun `restore sets del back to false`() {
            val deleted = DbSnapshot(mapOf(Table.Accounts to listOf(accountRow + ("del" to true))))
            val cs = ChangeSetBuilder(deleted, audit, "desktop")
                .restore(Table.Accounts, 5).build("restore")
            val op = cs.ops[0].shouldBeInstanceOf<Op.SetDeleted>()
            op.base shouldBe true
            op.new shouldBe false
        }
    }

    @Nested
    inner class HardDelete {
        @Test
        fun `captures the full base row`() {
            val cs = builder().hardDelete(Table.Rates, 201).build("delete rate")
            val op = cs.ops[0].shouldBeInstanceOf<Op.HardDelete>()
            op.baseRow shouldBe rateRow
        }
    }

    @Nested
    inner class ApplyTreeChanges {
        @Test
        fun `produces one update per item with merged fields`() {
            val changes = listOf(
                TreeChange.ParentChanged(10, old = 1L, new = null),
                TreeChange.OrdChanged(10, old = 1L, new = 3),
            )
            val cs = builder().applyTreeChanges(changes).build("reparent")
            cs.ops shouldHaveSize 1
            val update = cs.ops[0].shouldBeInstanceOf<Op.Update>()
            update.row shouldBe RowRef.Existing(10)
            update.fields["Parent_ID"] shouldBe FieldChange(base = 1L, new = null)
            update.fields["Ord"] shouldBe FieldChange(base = 1L, new = 3L)
            update.fields.containsKey("Editor") shouldBe true
            update.fields.containsKey("DateEdit") shouldBe true
        }

        @Test
        fun `Ord swap produces two updates`() {
            val changes = listOf(
                TreeChange.OrdChanged(10, old = 1L, new = 2),
                TreeChange.OrdChanged(11, old = 2L, new = 1),
            )
            val cs = builder().applyTreeChanges(changes).build("swap")
            cs.ops shouldHaveSize 2
            val first = cs.ops[0].shouldBeInstanceOf<Op.Update>()
            first.fields["Ord"] shouldBe FieldChange(base = 1L, new = 2L)
            val second = cs.ops[1].shouldBeInstanceOf<Op.Update>()
            second.fields["Ord"] shouldBe FieldChange(base = 2L, new = 1L)
        }
    }

    @Nested
    inner class Build {
        @Test
        fun `changeset carries the device and author`() {
            val cs = builder().softDelete(Table.Accounts, 5).build("test")
            cs.device shouldBe "desktop"
            cs.author shouldBe 1
            cs.description shouldBe "test"
            cs.ops shouldHaveSize 1
        }

        @Test
        fun `nextTempKey produces sequential keys`() {
            val b = builder()
            b.nextTempKey() shouldBe TempKey("t1")
            b.nextTempKey() shouldBe TempKey("t2")
        }

        @Test
        fun `empty builder produces a changeset with no ops`() {
            val cs = builder().build("empty")
            cs.ops shouldHaveSize 0
        }
    }
}
