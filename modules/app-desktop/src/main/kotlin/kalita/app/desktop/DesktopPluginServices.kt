// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.app.cli.JdkPluginHttpClient
import kalita.platform.SecretStore
import kalita.plugin.api.DatabaseContext
import kalita.plugin.api.PluginLogLevel
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import kalita.plugin.host.PluginHostServices

/**
 * The desktop shell's real services behind every plugin's [kalita.plugin.api.PluginHost]
 * (dev_plan.md §9.11): the OS secret store the Nextcloud password already lives in, the JDK's
 * own HTTP client (no second network stack next to `sync`'s OkHttp just for plugins), and a log
 * line on stderr — the same place the rest of the desktop app's diagnostics go.
 */
internal fun desktopPluginServices(secretStore: SecretStore, database: () -> DatabaseContext?): PluginHostServices =
    PluginHostServices(
        httpClient = JdkPluginHttpClient(),
        secretStore = object : PluginSecretStore {
            override fun get(key: String): String? = secretStore.get(key)
            override fun put(key: String, value: String) = secretStore.put(key, value)
            override fun remove(key: String) = secretStore.remove(key)
        },
        logger = PluginLogger { level: PluginLogLevel, message: String -> System.err.println("kalita plugin ${level.name.lowercase()}: $message") },
        database = database,
    )
