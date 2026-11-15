// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.plugin.api.PluginHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * [PluginHttpClient] for Android on the platform's own `HttpURLConnection` — `java.net.http`,
 * which the desktop shell uses, does not exist here, and one GET per currency does not justify
 * handing plugins `sync`'s OkHttp. The response is decoded with the charset its `Content-Type`
 * names (the Bank of Russia answers in windows-1251), UTF-8 otherwise.
 */
class AndroidPluginHttpClient : PluginHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): String = request(url, null, headers)

    override suspend fun post(url: String, body: String, headers: Map<String, String>): String = request(url, body, headers)

    private suspend fun request(url: String, body: String?, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status from $url")
            connection.inputStream.use { it.readBytes() }.toString(charsetOf(connection.contentType))
        } finally {
            connection.disconnect()
        }
    }

    internal companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000

        fun charsetOf(contentType: String?): Charset {
            val name = contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"')
            return name?.takeIf { it.isNotEmpty() }?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        }
    }
}
