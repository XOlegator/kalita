// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.plugin.reconcile.BankMappingStore
import kalita.plugin.reconcile.CategoryRule
import kalita.plugin.reconcile.Decision
import kalita.plugin.reconcile.LinkKind
import kalita.plugin.reconcile.Proposal
import kalita.plugin.statement.CsvColumns
import kalita.plugin.statement.StatementFilePlugin
import kalita.storage.api.SubmitResult
import kalita.storage.mdb.createDemoFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StatementImportTest {
    @Test
    fun `mapping edits persist changes and removals without touching other keys`(@TempDir dir: Path) {
        val store = BankMappingStore(dir.toFile())
        store.setAccount("bank1", 1)
        store.setAccount("bank2", 99)
        store.setCurrency("EUR", 2)
        store.setCurrency("USD", 3)
        val original = store.read()
        val accounts = mapOf("bank1" to 4L, "bank2" to 99L)
        val currencies = mapOf("EUR" to null, "USD" to 5L)
        store.read() shouldBe original
        saveBankMappings(store, original, accounts, currencies)
        BankMappingStore(dir.toFile()).read() shouldBe BankMappingStore.Mappings(
            mapOf("bank1" to 4L, "bank2" to 99L), mapOf("USD" to 5L),
        )
    }
    @Test
    fun `manual CSV mapping and ignored transactions survive reimport`(@TempDir dir: Path) = runBlocking {
        val fixture = createDemoFixture(dir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(dir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        val plugin = StatementFilePlugin()
        val bytes = "when;value;note\n2026-02-01;-45;Shop\n".toByteArray()
        val preview = plugin.previewCsv(bytes)
        preview.suggestedColumns shouldBe null
        val transactions = plugin.importCsv(bytes, "test.csv", 1, CsvColumns(date = 0, amount = 1, description = 2))
        val rule = CategoryRule(CategoryRule.Field.MERCHANT, CategoryRule.Match.CONTAINS, "Shop", 1, null)
        controller.categoryRules()!!.write(listOf(rule))
        controller.categoryRules()!!.read() shouldBe listOf(rule)
        val report = controller.reconcile(transactions, 1, 1)
        report.proposals.size shouldBe 1
        controller.applyImport(listOf(Decision.Ignore(report.proposals.single())), 1).shouldBeInstanceOf<SubmitResult.Applied>()
        val repeated = controller.reconcile(transactions, 1, 1)
        repeated.proposals.size shouldBe 0
        repeated.skippedAlreadyLinked shouldBe 1
        controller.closeDatabase()
    }

    @Test
    fun `import without an open database fails`(@TempDir dir: Path) {
        AndroidAppController(dir.toFile()).applyImport(emptyList(), 1).shouldBeInstanceOf<SubmitResult.Failed>()
    }

    @Test
    fun `same operation cannot be linked twice`(@TempDir dir: Path) {
        val fixture = createDemoFixture(dir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(dir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        val operation = (controller.state.value as AndroidAppState.Ready).dataset.operations.first()
        val plugin = StatementFilePlugin()
        val txs = plugin.importCsv("x;y\n2026-02-01;-45\n2026-02-02;-45".toByteArray(), "test.csv", 1, CsvColumns(date = 0, amount = 1))
        val decisions = txs.map { tx -> Decision.Link(Proposal.Exact(tx, operation, emptyList()), listOf(operation), LinkKind.MANUAL) }
        controller.applyImport(decisions, 1).shouldBeInstanceOf<SubmitResult.Failed>()
        controller.closeDatabase()
    }
}