// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import io.qt.widgets.QApplication
import kalita.core.OperationDraft
import kalita.platform.NoopSecretStore
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

/**
 * Opt-in rendering check of the plugin screens (dev_plan.md §9.11, §11.4) under Qt's offscreen
 * platform: `-Dkalita.ui.preview=/some/dir` makes it build the real dialogs over a real fixture
 * database and save each one as a PNG there — a way to eyeball layouts without a display, the
 * same trick `--self-test` uses for the main window. Skipped (not failed) without the property.
 */
class PluginUiPreviewTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `renders the plugin screens to PNG files`() {
        val outDir = System.getProperty("kalita.ui.preview")?.let(::File)
        assumeTrue(outDir != null, "set -Dkalita.ui.preview=<dir> to render")
        outDir!!.mkdirs()
        QApplication.initialize(arrayOf("preview", "-platform", "offscreen"))
        try {
            val settings = AppSettings(tempDir.resolve("config"))
            val ctrl = AppController(settings, NoopSecretStore, externalPluginsDirectory = null)
            ctrl.openDatabase(createDemoFixture(tempDir.resolve("Data.mdb").toFile(), operationCount = 3))
            ctrl.addCurrency("Динар", null)
            ctrl.addBudgetItem(null, "Расход")
            var dataset = (ctrl.state.value as AppState.Ready).dataset
            val rsd = dataset.currencies.single { it.name == "Динар" }.id
            val card = dataset.accounts.single { it.name == "Карта" }.id
            val cash = dataset.accounts.single { it.name == "Кошелёк" }.id
            val expense = dataset.operationTypes.first { it.kind == kalita.core.OperationKind.Expense }.id
            val item = dataset.budgetItems.single { it.name == "Расход" }.id
            ctrl.addOperation(OperationDraft(LocalDate.of(2026, 11, 8), BigDecimal("1650.00"), rsd, item, expense, "чек", null, card, null))
            ctrl.addOperation(OperationDraft(LocalDate.of(2026, 11, 3), BigDecimal("700.00"), rsd, item, expense, "ошибочно наличными", null, cash, null))
            ctrl.addOperation(OperationDraft(LocalDate.of(2026, 11, 2), BigDecimal("55.00"), rsd, item, expense, "кофе", null, card, null))
            dataset = (ctrl.state.value as AppState.Ready).dataset

            val transactions = ctrl.readStatement("import-apibank", File(javaClass.getResource("/statement-text.xls")!!.toURI())).getOrThrow()
            val report = ctrl.reconcile(transactions, card, rsd)
            val importDialog = StatementImportDialog(null, dataset, report, card, "Карта · RSD")
            importDialog.show()
            importDialog.grab().save(File(outDir, "import-dialog.png").path) shouldBe true

            val plugins = PluginsDialog(null, ctrl.plugins) {}
            plugins.show()
            plugins.grab().save(File(outDir, "plugins-dialog.png").path) shouldBe true

            val backup = ctrl.plugins.entry("backup")!!
            val settingsDialog = PluginSettingsDialog(null, ctrl.plugins, backup)
            settingsDialog.show()
            settingsDialog.grab().save(File(outDir, "backup-settings.png").path) shouldBe true

            val mapping = BankMappingDialog(null, dataset, ctrl.bankMappings()!!, listOf("OLEG EXAMPLE"), listOf("RSD", "EUR"), requireAll = true)
            mapping.show()
            mapping.grab().save(File(outDir, "bank-mapping.png").path) shouldBe true
            ctrl.closeDatabase()
        } finally {
            QApplication.shutdown()
        }
    }
}
