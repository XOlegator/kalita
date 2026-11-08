// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private data class StoredFile(val bytes: ByteArray, val etag: String, val isFolder: Boolean = false)

/**
 * Minimal stand-in for the slice of Nextcloud's WebDAV + Login Flow v2 that [NextcloudClient]/
 * [LoginFlowV2] actually use — no new test dependency (JDK's own [HttpServer], same trick as
 * `AgentChannel`'s tests use `java.nio` directly). Reproduces exactly the behaviour already
 * confirmed live against a real server (dev_plan.md §10, Этап 9 срез 1): a stale `If-Match`
 * answers `412` with the current ETag in the response header; `GET` returns `OC-Checksum`;
 * `If-None-Match: *` on an existing path answers `412`.
 */
class FakeNextcloudServer(val loginName: String = "xolegator") : AutoCloseable {
    private val files = ConcurrentHashMap<String, StoredFile>()
    // ConcurrentHashMap allows no null values, so "pending" is simply "absent" — a token that was
    // never issued and one that's issued but not yet confirmed answer the same 404 either way.
    private val pendingLogins = ConcurrentHashMap<String, NextcloudCredentials>()
    private val etagCounter = AtomicLong()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    val baseUrl: String = run {
        server.createContext("/remote.php/dav/files/$loginName", ::handleDav)
        server.createContext("/index.php/login/v2", ::handleLoginStart)
        server.createContext("/login/v2/poll", ::handleLoginPoll)
        server.start()
        "http://localhost:${server.address.port}"
    }

    /** Seeds a file as if a previous `put` had already landed it — returns the ETag to build tests on. */
    fun seedFile(path: String, bytes: ByteArray): String {
        val etag = "e${etagCounter.incrementAndGet()}"
        files[normalize(path)] = StoredFile(bytes, etag)
        return etag
    }

    fun seedFolder(path: String) {
        files[normalize(path)] = StoredFile(ByteArray(0), "folder", isFolder = true)
    }

    fun currentEtag(path: String): String? = files[normalize(path)]?.etag

    /** Simulates the user finishing the browser side of Login Flow v2 for [token]. */
    fun confirmLogin(token: String, credentials: NextcloudCredentials) {
        pendingLogins[token] = credentials
    }

    override fun close() = server.stop(0)

    private fun normalize(path: String) = path.trim('/')

    private fun handleDav(exchange: HttpExchange) {
        val path = normalize(URLDecoder.decode(exchange.requestURI.path.removePrefix("/remote.php/dav/files/$loginName"), Charsets.UTF_8))
        when (exchange.requestMethod) {
            "PROPFIND" -> handlePropfind(exchange, path)
            "GET" -> handleGet(exchange, path)
            "PUT" -> handlePut(exchange, path)
            else -> exchange.sendEmpty(405)
        }
    }

    private fun handlePropfind(exchange: HttpExchange, path: String) {
        exchange.requestBody.readBytes() // must be drained before responding, or HttpServer corrupts the next keep-alive request
        val depth = exchange.requestHeaders.getFirst("Depth")
        if (depth == "1") {
            // Direct children only: one more path segment than `path` itself, no "/" after that.
            val prefix = if (path.isEmpty()) "" else "$path/"
            val children = files.entries.filter { (p, _) ->
                p != path && p.startsWith(prefix) && !p.removePrefix(prefix).contains('/')
            }
            exchange.sendXml(207, multistatus(buildList {
                add(path to files[path])
                children.forEach { (p, f) -> add(p to f) }
            }))
            return
        }
        val file = files[path] ?: return exchange.sendEmpty(404)
        exchange.sendXml(207, multistatus(listOf(path to file)))
    }

    private fun handleGet(exchange: HttpExchange, path: String) {
        exchange.requestBody.readBytes() // drained for the same reason as in handlePropfind
        val file = files[path] ?: return exchange.sendEmpty(404)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(file.bytes).joinToString("") { "%02x".format(it) }
        exchange.responseHeaders.add("ETag", "\"${file.etag}\"")
        exchange.responseHeaders.add("OC-Checksum", "SHA1:$sha1")
        exchange.sendResponseHeaders(200, file.bytes.size.toLong())
        exchange.responseBody.use { it.write(file.bytes) }
    }

    private fun handlePut(exchange: HttpExchange, path: String) {
        val ifMatch = exchange.requestHeaders.getFirst("If-Match")?.trim('"')
        val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
        val existing = files[path]
        if (ifNoneMatch == "*" && existing != null) return exchange.sendPreconditionFailed(existing.etag)
        if (ifMatch != null && existing?.etag != ifMatch) return exchange.sendPreconditionFailed(existing?.etag)
        val bytes = exchange.requestBody.readBytes()
        val newEtag = "e${etagCounter.incrementAndGet()}"
        files[path] = StoredFile(bytes, newEtag)
        exchange.responseHeaders.add("ETag", "\"$newEtag\"")
        exchange.sendEmpty(204)
    }

    private fun handleLoginStart(exchange: HttpExchange) {
        exchange.requestBody.readBytes() // drained for the same reason as in handlePropfind
        if (exchange.requestMethod != "POST") return exchange.sendEmpty(405)
        val token = "token${etagCounter.incrementAndGet()}"
        exchange.sendJson(
            200,
            """{"poll":{"token":"$token","endpoint":"$baseUrl/login/v2/poll"},"login":"$baseUrl/login/v2/flow/$token"}""",
        )
    }

    private fun handleLoginPoll(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return exchange.sendEmpty(405)
        val form = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val token = form.substringAfter("token=").substringBefore('&')
        val credentials = pendingLogins[token]
        if (credentials == null) return exchange.sendEmpty(404)
        exchange.sendJson(
            200,
            """{"server":"${credentials.server}","loginName":"${credentials.loginName}","appPassword":"${credentials.appPassword}"}""",
        )
    }

    private fun multistatus(entries: List<Pair<String, StoredFile?>>): String {
        val responses = entries.filter { it.second != null }.joinToString("") { (path, file) ->
            file!!
            val resourceType = if (file.isFolder) "<d:collection/>" else ""
            """<d:response><d:href>/remote.php/dav/files/$loginName/$path</d:href><d:propstat><d:prop>
                <d:getetag>&quot;${file.etag}&quot;</d:getetag>
                <d:getcontentlength>${file.bytes.size}</d:getcontentlength>
                <d:resourcetype>$resourceType</d:resourcetype>
                </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
        }
        return """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">$responses</d:multistatus>"""
    }

    private fun HttpExchange.sendEmpty(code: Int) {
        sendResponseHeaders(code, -1)
        close()
    }

    private fun HttpExchange.sendPreconditionFailed(currentEtag: String?) {
        currentEtag?.let { responseHeaders.add("ETag", "\"$it\"") }
        sendEmpty(412)
    }

    private fun HttpExchange.sendXml(code: Int, body: String) {
        responseHeaders.add("Content-Type", "application/xml")
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.sendJson(code: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
