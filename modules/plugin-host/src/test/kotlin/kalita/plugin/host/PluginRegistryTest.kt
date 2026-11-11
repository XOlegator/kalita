// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.host

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.plugin.api.ActionOutcome
import kalita.plugin.api.ActionProvider
import kalita.plugin.api.DatabaseContext
import kalita.plugin.api.LifecycleHook
import kalita.plugin.api.PLUGIN_API_VERSION
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginAction
import kalita.plugin.api.PluginDescriptor
import kalita.plugin.api.PluginHost
import kalita.plugin.api.PluginLoadOutcome
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import kalita.plugin.api.PluginVersion
import kalita.plugin.api.SettingsField
import kalita.plugin.api.SettingsSchema
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

private class MemorySecrets : PluginSecretStore {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

private class RecordingPlugin(id: String = "rec") : Plugin, ActionProvider, LifecycleHook {
    override val descriptor = PluginDescriptor(
        id, "Recording", PluginVersion(0, 1, 0), PLUGIN_API_VERSION,
        SettingsSchema(
            listOf(
                SettingsField.Toggle("flag", "Flag", default = true),
                SettingsField.Number("keep", "Keep", default = 5.0),
                SettingsField.Secret("token", "Token"),
                SettingsField.Text("name", "Name", required = true, default = "x"),
            ),
        ),
    )
    var initialized = 0
    var lastSettings: Map<String, Any?> = emptyMap()
    var lastToken: String? = null
    var closedWith: DatabaseContext? = null
    var failClose = false
    var throwOnInit = false

    override fun initialize(host: PluginHost, settings: Map<String, Any?>) {
        if (throwOnInit) error("boom")
        initialized++
        lastSettings = settings
        lastToken = host.secretStore.get("token")
    }

    override val actions = listOf(PluginAction("hello", "Hello", requiresDatabase = false))
    override suspend fun perform(actionId: String, host: PluginHost) = ActionOutcome.Done("hi from ${host.database()?.displayName}")

    override suspend fun beforeClose(database: DatabaseContext, host: PluginHost): ActionOutcome {
        closedWith = database
        return if (failClose) ActionOutcome.Failed("no space") else ActionOutcome.Done()
    }
}

class PluginRegistryTest {
    @TempDir
    lateinit var dir: Path

    private val secrets = MemorySecrets()
    private val logged = mutableListOf<String>()
    private var database: DatabaseContext? = null

    private fun services() = PluginHostServices(NoNetworkHttpClient, secrets, PluginLogger { _, m -> logged += m }) { database }

    private fun registry(vararg plugins: Plugin, store: PluginSettingsStore = PluginSettingsStore(dir.resolve("plugins.json").toFile())) =
        PluginRegistry(plugins.map { PluginLoadOutcome.Loaded(it) to false }, store, services())

    @Test
    fun `loaded plugins are initialized with defaults and listed as active`() {
        val plugin = RecordingPlugin()
        val registry = registry(plugin)

        plugin.initialized shouldBe 1
        plugin.lastSettings shouldBe mapOf("flag" to true, "keep" to 5.0, "name" to "x")
        registry.entries.single().active shouldBe true
        registry.actions().map { it.second.id } shouldBe listOf("hello")
    }

    @Test
    fun `disabling keeps the entry but drops it from extensions until re-enabled`() {
        val plugin = RecordingPlugin()
        val registry = registry(plugin)

        registry.setEnabled("rec", false)
        registry.entries.single().enabled shouldBe false
        registry.extensions<ActionProvider>() shouldHaveSize 0

        registry.setEnabled("rec", true)
        plugin.initialized shouldBe 2
        registry.extensions<ActionProvider>() shouldHaveSize 1
    }

    @Test
    fun `settings round-trip persists plain values, puts secrets in the secret store only, and re-initializes`(@TempDir other: Path) {
        val plugin = RecordingPlugin()
        val file = dir.resolve("plugins.json").toFile()
        val registry = registry(plugin, store = PluginSettingsStore(file))

        registry.updateSettings("rec", mapOf("flag" to false, "keep" to "3", "token" to "s3", "name" to "n")) shouldBe emptyList()

        plugin.initialized shouldBe 2
        plugin.lastSettings shouldBe mapOf("flag" to false, "keep" to 3.0, "name" to "n")
        plugin.lastToken shouldBe "s3"
        secrets.map.keys shouldBe setOf("plugin|rec|token")
        file.readText().contains("s3") shouldBe false
        registry.hasSecret("rec", "token") shouldBe true

        // An empty secret on the next save means "unchanged", not "clear".
        registry.updateSettings("rec", mapOf("flag" to false, "keep" to "4", "token" to "", "name" to "n")) shouldBe emptyList()
        secrets.map["plugin|rec|token"] shouldBe "s3"

        // Reopened from disk: the same values come back through the store.
        val again = RecordingPlugin()
        registry(again, store = PluginSettingsStore(file))
        again.lastSettings shouldBe mapOf("flag" to false, "keep" to 4.0, "name" to "n")
    }

    @Test
    fun `invalid settings are reported and nothing is saved`() {
        val plugin = RecordingPlugin()
        val registry = registry(plugin)

        val errors = registry.updateSettings("rec", mapOf("name" to "", "keep" to "many"))

        errors.map { it.fieldId } shouldBe listOf("keep", "name")
        plugin.initialized shouldBe 1
    }

    @Test
    fun `an exception in initialize marks the entry and keeps the plugin out of extensions`() {
        val plugin = RecordingPlugin().apply { throwOnInit = true }
        val registry = registry(plugin)

        val entry = registry.entries.single()
        entry.active shouldBe false
        entry.problem shouldBe "initialize failed: boom"
        registry.extensions<LifecycleHook>() shouldHaveSize 0
        logged.single().startsWith("[rec] initialize failed") shouldBe true
    }

    @Test
    fun `incompatible plugins are listed with their reason and no instance`() {
        val descriptor = PluginDescriptor("old", "Old", PluginVersion(1, 0, 0), PluginVersion(0, 9, 0))
        val registry = PluginRegistry(
            listOf(PluginLoadOutcome.Incompatible(descriptor, "too old") to true),
            PluginSettingsStore(dir.resolve("p.json").toFile()),
            services(),
        )
        val entry = registry.entries.single()
        entry.plugin shouldBe null
        entry.problem shouldBe "too old"
        entry.external shouldBe true
        entry.active shouldBe false
    }

    @Test
    fun `actions and beforeClose reach the plugin through a scoped host`() = runTest {
        val plugin = RecordingPlugin()
        val registry = registry(plugin)
        database = DatabaseContext("Data.mdb", File("/x/Data.mdb"), File("/x/data"))

        registry.perform("rec", "hello") shouldBe ActionOutcome.Done("hi from Data.mdb")
        registry.perform("nobody", "hello").shouldBeInstanceOf<ActionOutcome.Failed>()

        registry.beforeClose(database!!) shouldBe emptyList()
        plugin.closedWith shouldBe database

        plugin.failClose = true
        val failures = registry.beforeClose(database!!)
        failures.single().second.message shouldBe "no space"
    }

    @Test
    fun `a duplicate plugin id keeps the first and logs the second`() {
        val registry = registry(RecordingPlugin(), RecordingPlugin())
        registry.entries shouldHaveSize 1
        logged.single().contains("duplicate plugin id") shouldBe true
    }
}
