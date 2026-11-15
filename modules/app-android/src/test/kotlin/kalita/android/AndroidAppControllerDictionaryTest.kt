// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import kalita.plugin.api.PluginHttpClient
import kalita.plugin.host.RateLookup
import kalita.presentation.Strings
import kalita.storage.api.DbLocation
import kalita.storage.api.SubmitResult
import kalita.storage.local.LocalStorageProvider
import kalita.storage.local.defaultDataDirectory
import kalita.storage.mdb.createDemoFixture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    @Test
    fun `a new currency gets its code from the name, a looked-up rate, and today's rates load in one tap`(@TempDir tempDir: Path): Unit = runBlocking {
        val requests = mutableListOf<String>()
        val http = object : PluginHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): String {
                requests += url
                return when {
                    "XML_daily" in url -> """<ValCurs><Valute ID="R01805F"><CharCode>RSD</CharCode><Nominal>100</Nominal></Valute></ValCurs>"""
                    else -> """<ValCurs><Record Date="14.11.2026" Id="x"><Nominal>100</Nominal><Value>77,2483</Value></Record></ValCurs>"""
                }
            }

            override suspend fun post(url: String, body: String, headers: Map<String, String>): String = error("unused")
        }
        val fixture = createDemoFixture(tempDir.resolve("picked.mdb").toFile(), operationCount = 1)
        val controller = AndroidAppController(tempDir.resolve("private").toFile().apply { mkdirs() }, rateHttpClient = http)
        fixture.inputStream().use { controller.importAndOpen(it) }
        val day = LocalDate.of(2026, 11, 15)
        controller.addCurrency("Рубль", null).shouldBeInstanceOf<SubmitResult.Applied>()

        val found = controller.lookupNewCurrencyRate("Сербский динар", null, day).shouldBeInstanceOf<RateLookup.Found>()
        found.date shouldBe LocalDate.of(2026, 11, 14)
        controller.addCurrency("Сербский динар", null, found.value.toDouble(), found.date).shouldBeInstanceOf<SubmitResult.Applied>()
        val dinar = ready(controller).dataset.currencies.single { it.name == "Сербский динар" }
        dinar.cbId shouldBe "RSD"
        ready(controller).dataset.rates.single { it.currencyId == dinar.id }.value shouldBe 0.772483

        // Already stored as it is: nothing new, nothing written twice.
        controller.loadRates(day) shouldContain Strings.RATE_LOAD_NOTHING_NEW
        ready(controller).dataset.rates.count { it.currencyId == dinar.id } shouldBe 1
        requests.any { "VAL_NM_RQ=R01805F" in it } shouldBe true
    }

    @Test
    fun `typed rates accept a comma and reject what is not a positive number`() {
        parseRate(" 0,772483 ") shouldBe 0.772483
        parseRate("1 234.5") shouldBe 1234.5
        parseRate("") shouldBe null
        parseRate("0") shouldBe null
        parseRate("курс") shouldBe null
        AndroidPluginHttpClient.charsetOf("application/xml; charset=windows-1251").name() shouldBe "windows-1251"
        AndroidPluginHttpClient.charsetOf("text/plain") shouldBe Charsets.UTF_8
        AndroidPluginHttpClient.charsetOf(null) shouldBe Charsets.UTF_8
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
        runBlocking { controller.openIfExists() }

        val result = controller.addAccount("Новый счёт")

        result.shouldBeInstanceOf<SubmitResult.Failed>()
        blockingSession.close()
    }
}
