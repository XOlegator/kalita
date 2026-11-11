// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.host

import kalita.plugin.api.DatabaseContext
import kalita.plugin.api.PluginHost
import kalita.plugin.api.PluginHttpClient
import kalita.plugin.api.PluginLogLevel
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import java.time.Clock

/**
 * The shell's one set of real services, from which [forPlugin] cuts a [PluginHost] per plugin:
 * same HTTP client, clock and database lookup, but secrets keyed under `plugin|<id>|<key>` so
 * one plugin can never read another's token (dev_plan.md §9.11 "namespaced per
 * [PluginDescriptor.id]"), and log lines prefixed with the plugin id so a host log stays readable.
 */
class PluginHostServices(
    val httpClient: PluginHttpClient,
    private val secretStore: PluginSecretStore,
    private val logger: PluginLogger,
    val clock: Clock = Clock.systemDefaultZone(),
    private val database: () -> DatabaseContext?,
) {
    fun forPlugin(pluginId: String): PluginHost = Scoped(pluginId)

    /** The same scoping [forPlugin] gives a plugin — the registry writes a plugin's secrets through this. */
    fun secretStoreFor(pluginId: String): PluginSecretStore = ScopedSecrets(pluginId)

    private inner class ScopedSecrets(private val pluginId: String) : PluginSecretStore {
        private fun key(key: String) = "plugin|$pluginId|$key"
        override fun get(key: String): String? = secretStore.get(key(key))
        override fun put(key: String, value: String) = secretStore.put(key(key), value)
        override fun remove(key: String) = secretStore.remove(key(key))
    }

    private inner class Scoped(private val pluginId: String) : PluginHost {
        override val httpClient: PluginHttpClient get() = this@PluginHostServices.httpClient
        override val secretStore: PluginSecretStore = ScopedSecrets(pluginId)
        override val logger = PluginLogger { level: PluginLogLevel, message: String -> this@PluginHostServices.logger.log(level, "[$pluginId] $message") }
        override val clock: Clock get() = this@PluginHostServices.clock
        override fun database(): DatabaseContext? = this@PluginHostServices.database()
    }
}

/** A [PluginHttpClient] for shells that give plugins no network at all (nothing in this slice needs one). */
object NoNetworkHttpClient : PluginHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): String = throw IllegalStateException("network access is not available to plugins in this shell")
    override suspend fun post(url: String, body: String, headers: Map<String, String>): String = get(url, headers)
}
