// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.platform.SecretStore
import kalita.plugin.api.DatabaseContext
import kalita.plugin.api.PluginHttpClient
import kalita.plugin.api.PluginLogLevel
import kalita.plugin.api.PluginLogger
import kalita.plugin.api.PluginSecretStore
import kalita.plugin.host.PluginHostServices
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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

internal class JdkPluginHttpClient : PluginHttpClient {
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build() }

    override suspend fun get(url: String, headers: Map<String, String>): String =
        send(HttpRequest.newBuilder(URI.create(url)).GET(), headers)

    override suspend fun post(url: String, body: String, headers: Map<String, String>): String =
        send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body)), headers)

    private suspend fun send(builder: HttpRequest.Builder, headers: Map<String, String>): String {
        builder.timeout(Duration.ofSeconds(60))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) throw java.io.IOException("HTTP ${response.statusCode()} from ${response.uri()}")
        return response.body()
    }
}
