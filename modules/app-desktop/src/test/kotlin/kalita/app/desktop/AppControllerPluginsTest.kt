// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.core.OperationDraft
import kalita.platform.NoopSecretStore
import kalita.plugin.reconcile.Decision
import kalita.plugin.reconcile.LinkKind
import kalita.plugin.reconcile.Proposal
import kalita.storage.api.SubmitResult
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

/** The desktop shell's plugin wiring (dev_plan.md §9.11, §11): built-in discovery, the close hook, and the statement import pipeline without any Qt. */
class AppControllerPluginsTest {
    @TempDir
    lateinit var tempDir: Path

    private fun controller(): Pair<AppController, AppSettings> {
        val settings = AppSettings(tempDir.resolve("config"))
        return AppController(settings, NoopSecretStore, externalPluginsDirectory = null) to settings
    }

    private fun fixture(): File = createDemoFixture(tempDir.resolve("Data.mdb").toFile(), operationCount = 3)

    @Test
    fun `built-in plugins are discovered, active and reachable through the registry`() {
        val (ctrl, _) = controller()

        ctrl.plugins.entries.filter { it.active }.map { it.descriptor.id } shouldContainAll listOf("backup", "import-apibank")
        ctrl.statementImporters().map { it.first.id } shouldBe listOf("import-apibank")
        ctrl.plugins.actions().map { it.second.id } shouldBe listOf("backup-now")
        ctrl.databaseContext() shouldBe null
    }

    @Test
    fun `closing the database runs the backup hook when enabled and reports its failure as a warning`() {
        val (ctrl, _) = controller()
        val target = tempDir.resolve("copies").toFile()
        ctrl.plugins.updateSettings("backup", mapOf("backupOnClose" to true, "directory" to target.path, "keep" to 2.0)).shouldBeEmpty()
        val db = fixture()

        ctrl.openDatabase(db).shouldBeInstanceOf<OpenResult.Success>()
        ctrl.databaseContext()!!.file shouldBe db
        ctrl.closeDatabase()

        val copies = target.list()!!.filter { it.startsWith("Data-") && it.endsWith(".mdb") }
        copies shouldHaveSize 1
        File(target, copies[0]).length() shouldBe db.length()
        ctrl.consumeWarnings().shouldBeEmpty()

        // A folder that cannot be created turns into a warning, never an exception out of close.
        val blocker = tempDir.resolve("blocker").toFile().apply { writeText("x") }
        ctrl.plugins.updateSettings("backup", mapOf("backupOnClose" to true, "directory" to blocker.path, "keep" to 2.0)).shouldBeEmpty()
        ctrl.openDatabase(db)
        ctrl.closeDatabase()
        ctrl.consumeWarnings().single().startsWith("Резервные копии базы:") shouldBe true
        ctrl.consumeWarnings().shouldBeEmpty()
    }

    @Test
    fun `disabled backup plugin leaves close alone`() {
        val (ctrl, _) = controller()
        ctrl.plugins.setEnabled("backup", false)
        val db = fixture()
        ctrl.openDatabase(db)
        ctrl.closeDatabase()
        db.parentFile.list()!!.none { it.startsWith("Data-") } shouldBe true
    }

    @Test
    fun `statement import end to end - read, map, reconcile, apply, and a second run is idempotent`() {
        val (ctrl, _) = controller()
        ctrl.openDatabase(fixture()).shouldBeInstanceOf<OpenResult.Success>()
        // The demo fixture has accounts and types but no currency or budget items — add what the import needs.
        ctrl.addCurrency("Динар", null).shouldBeInstanceOf<SubmitResult.Applied>()
        ctrl.addBudgetItem(null, "Расход").shouldBeInstanceOf<SubmitResult.Applied>()
        val dataset = (ctrl.state.value as AppState.Ready).dataset
        val rsd = dataset.currencies.single { it.name == "Динар" }.id
        val card = dataset.accounts.single { it.name == "Карта" }.id
        val cash = dataset.accounts.single { it.name == "Кошелёк" }.id
        val expense = dataset.operationTypes.first { it.kind == kalita.core.OperationKind.Expense }.id
        val item = dataset.budgetItems.single { it.name == "Расход" }.id
        // One matching row (exact), one entered on the wrong account (similar elsewhere), nothing for the rest.
        ctrl.addOperation(OperationDraft(LocalDate.of(2026, 11, 8), BigDecimal("1650.00"), rsd, item, expense, "чек", null, card, null)).shouldBeInstanceOf<SubmitResult.Applied>()
        ctrl.addOperation(OperationDraft(LocalDate.of(2026, 11, 3), BigDecimal("700.00"), rsd, item, expense, "ошибочно наличными", null, cash, null)).shouldBeInstanceOf<SubmitResult.Applied>()

        val statement = File(javaClass.getResource("/statement-text.xls")!!.toURI())
        val transactions = ctrl.readStatement("import-apibank", statement).getOrThrow()
        transactions shouldHaveSize 6
        transactions.map { it.bankAccount }.toSet() shouldBe setOf("IVAN TESTOV")

        val mappings = ctrl.bankMappings()!!
        mappings.setAccount("IVAN TESTOV", card)
        mappings.setCurrency("RSD", rsd)

        val report = ctrl.reconcile(transactions, card, rsd)
        report.skippedPending shouldBe 1
        report.exact shouldHaveSize 1
        report.exact.single().operation.comment shouldBe "чек"
        val new = report.new
        new shouldHaveSize 4 // second MAXI line, Yettel, ACME income, Konverzijska
        val yettel = new.single { it.transaction.description.startsWith("Yettel") }
        yettel.similarElsewhere.single().comment shouldBe "ошибочно наличными"
        val income = new.single { it.transaction.amount.signum() > 0 }
        income.draft.accountTo shouldBe card
        income.draft.accountFrom shouldBe null

        // Creating without a budget item is refused before anything is written.
        val maxi2 = new.single { it.transaction.description.startsWith("MAXI") }
        ctrl.applyImport(listOf(Decision.Create(maxi2, maxi2.draft)), card).shouldBeInstanceOf<SubmitResult.Failed>()

        val decisions = listOf(
            Decision.Link(report.exact.single(), listOf(report.exact.single().operation), LinkKind.EXACT),
            Decision.Create(maxi2, maxi2.draft.copy(budgetItemId = item)),
            Decision.MoveToAccount(yettel, yettel.similarElsewhere.single()),
            Decision.Ignore(new.single { it.transaction.description.startsWith("Konverzijska") }),
            // the ACME income line is left undecided (skipped)
        )
        ctrl.applyImport(decisions, card).shouldBeInstanceOf<SubmitResult.Applied>()

        val after = (ctrl.state.value as AppState.Ready).dataset
        val created = after.operations.single { it.comment == "MAXI 123, BEOGRAD" && !it.deleted }
        created.accountFrom shouldBe card
        created.amount!!.compareTo(BigDecimal("1650")) shouldBe 0
        after.operations.single { it.comment == "ошибочно наличными" }.accountFrom shouldBe card
        ctrl.readHistory().last().changeSet.description shouldBe "Импорт выписки: новых операций 1, перенесено на счёт 1"

        // FR-B6: the same statement again only offers what was skipped.
        val second = ctrl.reconcile(transactions, card, rsd)
        second.skippedAlreadyLinked shouldBe 4
        second.proposals.single().shouldBeInstanceOf<Proposal.New>().transaction.description shouldBe "ACME LTD, Mildenhall"
        second.notInBank.shouldBeEmpty()
        ctrl.reconLinks()!!.current().values.map { it.kind }.toSet() shouldBe setOf(LinkKind.EXACT, LinkKind.CREATED, LinkKind.MANUAL, LinkKind.IGNORED)
    }

    @Test
    fun `import on a read-only or closed database fails cleanly`() {
        val (ctrl, _) = controller()
        ctrl.applyImport(emptyList(), 1).shouldBeInstanceOf<SubmitResult.Failed>()
        ctrl.readStatement("nope", File("x.xls")).isFailure shouldBe true
        ctrl.reconcile(emptyList(), 1, 1).proposals.shouldBeEmpty()
    }
}
