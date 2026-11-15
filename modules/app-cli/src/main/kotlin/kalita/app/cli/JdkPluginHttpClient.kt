// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.cli

import kalita.plugin.api.PluginHttpClient
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The JDK's own HTTP client behind [PluginHttpClient] for the two JVM shells — the command line
 * here and the desktop application, which depends on this module (no second network stack next
 * to `sync`'s OkHttp just for plugins). The response charset comes from its `Content-Type`.
 */
class JdkPluginHttpClient : PluginHttpClient {
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
