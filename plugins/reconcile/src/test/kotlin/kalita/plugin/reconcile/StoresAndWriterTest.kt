// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.reconcile

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kalita.core.AuditStamp
import kalita.core.OperationDraft
import kalita.storage.api.ChangeSetBuilder
import kalita.storage.api.DbSnapshot
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class StoresAndWriterTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `bank mappings round-trip and can be cleared`() {
        val store = BankMappingStore(dir.toFile())
        store.accountFor("IVAN") shouldBe null

        store.setAccount("IVAN", CARD)
        store.setCurrency("rsd", RSD)
        store.setCurrency("EUR", EUR)

        BankMappingStore(dir.toFile()).accountFor("IVAN") shouldBe CARD
        store.currencyFor("RSD") shouldBe RSD
        store.read().currencies shouldBe mapOf("RSD" to RSD, "EUR" to EUR)
        store.setCurrency("EUR", null)
        store.read().currencies shouldBe mapOf("RSD" to RSD)
    }

    @Test
    fun `links are appended and the last line per bank transaction wins`() {
        val store = ReconLinkStore(dir.toFile())
        val at = Instant.parse("2026-11-11T10:00:00Z")
        store.append(listOf(ReconLink("apibank|a", listOf(1, 2), LinkKind.SUM, at, "desktop"), ReconLink("apibank|b", emptyList(), LinkKind.IGNORED, at, "desktop")))
        store.append(listOf(ReconLink("apibank|a", listOf(3), LinkKind.MANUAL, at.plusSeconds(1), "desktop")))

        store.readAll() shouldHaveSize 3
        store.linkedBankTransactions() shouldBe setOf("apibank|a", "apibank|b")
        store.linkedRows() shouldBe setOf(3L)
        store.current().getValue("apibank|a").kind shouldBe LinkKind.MANUAL
    }

    @Test
    fun `the writer turns decisions into inserts, account moves and links`() = runTest {
        val ds = dataset(op(1, "2026-11-08", "1650", from = CASH), op(2, "2026-11-09", "700"))
        val snapshot = DbSnapshot(
            mapOf(
                Table.Operations to listOf(
                    mapOf("id" to 1L, "AccountFrom" to CASH, "AccountTo" to null, "del" to false),
                    mapOf("id" to 2L, "AccountFrom" to CARD, "AccountTo" to null, "del" to false),
                ),
            ),
        )
        val report = Reconciler(ds).reconcile(
            listOf(tx("a", "2026-11-09", "-1650.00"), tx("b", "2026-11-09", "-700.00"), tx("c", "2026-11-09", "-5.00"), tx("d", "2026-11-09", "-6.00")),
            CARD, RSD, emptySet(), emptySet(),
        )
        val new = report.new.first { it.transaction.externalId == "a" }
        val create = report.new.first { it.transaction.externalId == "c" }
        val draft = create.draft.copy(budgetItemId = 44, comment = "edited")
        val decisions = listOf(
            Decision.MoveToAccount(new, new.similarElsewhere.single()),
            Decision.Link(report.exact.single(), listOf(report.exact.single().operation), LinkKind.EXACT),
            Decision.Create(create, draft),
            Decision.Ignore(report.new.first { it.transaction.externalId == "d" }),
        )
        val builder = ChangeSetBuilder(snapshot, AuditStamp(1, LocalDateTime.of(2026, 11, 11, 12, 0)), "test")

        val prepared = ReconcileWriter.prepare(builder, decisions, CARD)
        val changeSet = builder.build("import")

        prepared.insertCount shouldBe 1
        prepared.updateCount shouldBe 1
        prepared.isEmpty shouldBe false
        val insert = changeSet.ops.filterIsInstance<Op.Insert>().single()
        insert.values["Summa"] shouldBe BigDecimal("5.00")
        insert.values["Comment"] shouldBe "edited"
        insert.values["GroupBudget_ID"] shouldBe 44L
        insert.values["Date"] shouldBe LocalDate.parse("2026-11-09").atStartOfDay()
        val update = changeSet.ops.filterIsInstance<Op.Update>().single()
        update.fields.getValue("AccountFrom").new shouldBe CARD

        val links = prepared.links(mapOf(insert.tempKey to 99L), Instant.parse("2026-11-11T10:00:00Z"), "desktop")
        links.map { it.bankTx to it.kind } shouldBe listOf(
            "apibank|a" to LinkKind.MANUAL, "apibank|b" to LinkKind.EXACT, "apibank|d" to LinkKind.IGNORED, "apibank|c" to LinkKind.CREATED,
        )
        links.last().rows shouldBe listOf(99L)
        links.all { it.device == "desktop" } shouldBe true
        // An unresolved temp key (commit failed for that row) yields no CREATED link.
        prepared.links(mapOf(TempKey("nope") to 1L), Instant.EPOCH, "d").none { it.kind == LinkKind.CREATED } shouldBe true
    }

    @Test
    fun `only links and ignores make an empty change set`() {
        val ds = dataset(op(2, "2026-11-09", "700"))
        val builder = ChangeSetBuilder(DbSnapshot.EMPTY, AuditStamp(1, LocalDateTime.of(2026, 11, 11, 12, 0)), "test")
        val proposal = Proposal.Exact(tx("b", "2026-11-09", "-700.00"), ds.operations[0], emptyList())
        val prepared = ReconcileWriter.prepare(builder, listOf(Decision.Link(proposal, listOf(ds.operations[0]), LinkKind.EXACT)), CARD)
        prepared.isEmpty shouldBe true
        OperationDraft().date shouldBe null
    }
}
