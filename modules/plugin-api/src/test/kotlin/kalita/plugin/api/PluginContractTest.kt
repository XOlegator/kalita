// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * A stand-in for a real statement-import plugin (dev_plan.md §9.11, §11.5): proves a single
 * class can implement [Plugin] plus more than one extension point, read a secret the host
 * injected, and be discovered by a plain `is` check — the shape a loader built on top of this
 * module (not yet written, §9.2 Этап 9) will rely on.
 */
private class FakeStatementPlugin : Plugin, StatementImporter, Categorizer {
    override val descriptor = PluginDescriptor(
        id = "fake-statement",
        displayName = "Fake Bank",
        version = PluginVersion(0, 1, 0),
        requiredApiVersion = PLUGIN_API_VERSION,
        settings = SettingsSchema(listOf(SettingsField.Secret(id = "apiToken", label = "API token", required = true))),
    )

    override val supportedExtensions = setOf("csv")

    lateinit var token: String

    override fun initialize(host: PluginHost, settings: Map<String, Any?>) {
        token = host.secretStore.get("apiToken") ?: error("apiToken not provisioned")
    }

    override suspend fun import(fileBytes: ByteArray, fileName: String): List<BankTransaction> =
        listOf(
            BankTransaction(
                source = descriptor.id,
                externalId = "line-1",
                bankAccount = "***1234",
                postedAt = LocalDate.of(2026, 11, 1),
                operationAt = null,
                amount = java.math.BigDecimal("-350.00"),
                currency = "RSD",
                description = String(fileBytes),
                status = BankTransaction.Status.Posted,
            ),
        )

    override suspend fun suggest(description: String, merchant: String?, mcc: String?) =
        listOf(CategorizerSuggestion(budgetItemId = 42, accountId = null, counterpartyId = null, confidence = 0.9f))
}

class PluginContractTest {
    @Test
    fun `a plugin can implement several extension points and is found by a type check`() {
        val plugin = FakeStatementPlugin()
        val extensionPoints = listOf<Any>(plugin).filterIsInstance<StatementImporter>() +
            listOf<Any>(plugin).filterIsInstance<Categorizer>()
        extensionPoints shouldHaveSize 2
    }

    @Test
    fun `the plugin's required api version is compatible with the current host`() {
        val plugin = FakeStatementPlugin()
        isCompatible(plugin.descriptor.requiredApiVersion, PLUGIN_API_VERSION) shouldBe true
    }

    @Test
    fun `initialize reads its own secret from the host, not from anywhere else`() {
        val host = FakePluginHost()
        host.secretStore.put("apiToken", "s3cr3t")
        val plugin = FakeStatementPlugin()

        plugin.initialize(host, emptyMap())

        plugin.token shouldBe "s3cr3t"
    }

    @Test
    fun `import turns a statement file into bank transactions`() = kotlinx.coroutines.test.runTest {
        val plugin = FakeStatementPlugin()

        val transactions = plugin.import("Coffee shop".toByteArray(), "statement.csv")

        transactions shouldHaveSize 1
        transactions[0].source shouldBe "fake-statement"
        transactions[0].status shouldBe BankTransaction.Status.Posted
    }
}
