// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.storage.api.DbLocation
import kalita.storage.api.SubmitResult
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

// FR-A6's write path (dev_plan.md Этап 10) — mirrors kalita.app.desktop.AppControllerCrudTest's
// own dictionary tests 1:1, since AndroidAppController's new methods are the same ChangeSetBuilder
// calls under a different submitChange gate (already proven by AndroidAppControllerOperationsTest).
class AndroidAppControllerDictionaryTest {
    private fun openController(tempDir: Path): AndroidAppController {
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() })
        fixture.inputStream().use { controller.importAndOpen(it) }
        return controller
    }

    private fun ready(controller: AndroidAppController): AndroidAppState.Ready =
        controller.state.value as AndroidAppState.Ready

    // --- Accounts ---

    @Test
    fun `addAccount creates a new account and refreshes the dictionary presenter`(@TempDir tempDir: Path) {
        val controller = openController(tempDir)

        controller.addAccount("Новый счёт").shouldBeInstanceOf<SubmitResult.Applied>()

        ready(controller).dataset.accounts.any { it.name == "Новый счёт" && !it.deleted } shouldBe true
        controller.dictionary.state.value.accounts.any { it.name == "Новый счёт" } shouldBe true
    }

    @Test
    fun `updateAccount renames an existing account`(@TempDir tempDir: Path) {
        val controller = openController(tempDir)
        val id = ready(controller).dataset.accounts.first().id

        controller.updateAccount(id, "Переименованный").shouldBeInstanceOf<SubmitResult.Applied>()

        ready(controller).dataset.accounts.first { it.id == id }.name shouldBe "Переименованный"
    }

    @Test
    fun `softDeleteAccount and restoreAccount round-trip the deleted flag`(@TempDir tempDir: Path) {
        val controller = openController(tempDir)
        val id = ready(controller).dataset.accounts.first().id

        controller.softDeleteAccount(id).shouldBeInstanceOf<SubmitResult.Applied>()
        ready(controller).dataset.accounts.first { it.id == id }.deleted shouldBe true

        controller.restoreAccount(id).shouldBeInstanceOf<SubmitResult.Applied>()
        ready(controller).dataset.accounts.first { it.id == id }.deleted shouldBe false
    }

    // --- Counterparties ---

    @Test
    fun `addCounterparty, updateCounterparty and softDeleteCounterparty work on a fresh entry`(@TempDir tempDir: Path) {
        val controller = openController(tempDir)

        controller.addCounterparty("Продавец").shouldBeInstanceOf<SubmitResult.Applied>()
        val id = ready(controller).dataset.counterparties.first { it.name == "Продавец" }.id

        controller.updateCounterparty(id, "Поставщик").shouldBeInstanceOf<SubmitResult.Applied>()
        ready(controller).dataset.counterparties.first { it.id == id }.name shouldBe "Поставщик"

        controller.softDeleteCounterparty(id).shouldBeInstanceOf<SubmitResult.Applied>()
        ready(controller).dataset.counterparties.first { it.id == id }.deleted shouldBe true
    }

    // --- Currencies ---

    @Test
    fun `addCurrency stores the name and CB_ID, updateCurrency changes both`(@TempDir tempDir: Path) {
        val controller = openController(tempDir)

        controller.addCurrency("Евро", "R01239").shouldBeInstanceOf<SubmitResult.Applied>()
        val id = ready(controller).dataset.currencies.first { it.name == "Евро" }.id
        ready(controller).dataset.currencies.first { it.id == id }.cbId shouldBe "R01239"

        controller.updateCurrency(id, "Евро2", "R99999").shouldBeInstanceOf<SubmitResult.Applied>()
        val updated = ready(controller).dataset.currencies.first { it.id == id }
        updated.name shouldBe "Евро2"
        updated.cbId shouldBe "R99999"
    }

    // --- Budget items (tree, no reordering — FR-A6 explicitly leaves that to the desktop app) ---

    @Test
    fun `addBudgetItem creates a root item, a second call under it creates a child`(@TempDir tempDir: Path) {
        val controller = openController(tempDir)

        controller.addBudgetItem(null, "Продукты").shouldBeInstanceOf<SubmitResult.Applied>()
        val rootId = ready(controller).dataset.budgetItems.first { it.name == "Продукты" }.id
        ready(controller).dataset.budgetItems.first { it.id == rootId }.parentId shouldBe null

        controller.addBudgetItem(rootId, "Молочное").shouldBeInstanceOf<SubmitResult.Applied>()
        val childId = ready(controller).dataset.budgetItems.first { it.name == "Молочное" }.id
        ready(controller).dataset.budgetItems.first { it.id == childId }.parentId shouldBe rootId
    }

    @Test
    fun `updateBudgetItem, softDeleteBudgetItem and restoreBudgetItem round-trip a root item`(@TempDir tempDir: Path) {
        val controller = openController(tempDir)
        controller.addBudgetItem(null, "Транспорт")
        val id = ready(controller).dataset.budgetItems.first { it.name == "Транспорт" }.id

        controller.updateBudgetItem(id, "Поездки").shouldBeInstanceOf<SubmitResult.Applied>()
        ready(controller).dataset.budgetItems.first { it.id == id }.name shouldBe "Поездки"

        controller.softDeleteBudgetItem(id).shouldBeInstanceOf<SubmitResult.Applied>()
        ready(controller).dataset.budgetItems.first { it.id == id }.deleted shouldBe true

        controller.restoreBudgetItem(id).shouldBeInstanceOf<SubmitResult.Applied>()
        ready(controller).dataset.budgetItems.first { it.id == id }.deleted shouldBe false
    }

    // --- Shared gate (one representative check — the gate itself is already covered exhaustively
    // by AndroidAppControllerOperationsTest's read-only tests; this just proves the new methods
    // route through the same submitChange, not a bypass) ---

    @Test
    fun `addAccount refuses to write when the session is read-only`(@TempDir tempDir: Path) {
        val privateDir = tempDir.resolve("private").toFile().apply { mkdirs() }
        val dbFile = File(privateDir, "Data.mdb")
        createDemoFixture(dbFile, operationCount = 1)
        val blockingSession = LocalStorageProvider.open(DbLocation.Local(dbFile, defaultDataDirectory(dbFile)))
        val controller = AndroidAppController(privateDir)
        controller.openIfExists()

        val result = controller.addAccount("Новый счёт")

        result.shouldBeInstanceOf<SubmitResult.Failed>()
        blockingSession.close()
    }
}
