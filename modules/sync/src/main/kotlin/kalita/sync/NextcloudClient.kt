// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/** One WebDAV entry from [NextcloudClient.list] — a file or folder under the probed path. */
data class NextcloudEntry(val path: String, val isFolder: Boolean, val etag: String?, val size: Long?)

/** Metadata-only probe result (§10.2's `PROPFIND` poll) — no body downloaded. */
data class NextcloudFileInfo(val etag: String, val size: Long)

/** A downloaded file: bytes plus the ETag/checksum the server returned alongside them. */
data class NextcloudFile(val bytes: ByteArray, val etag: String, val sha1: String?)

sealed interface NextcloudPutResult {
    /** Uploaded; [etag] is the new version's ETag, straight from the PUT response — no extra round trip. */
    data class Applied(val etag: String) : NextcloudPutResult

    /** `If-Match`/`If-None-Match` was not satisfied — someone else changed (or already created) the file first. */
    data class PreconditionFailed(val currentEtag: String?) : NextcloudPutResult

    data class Failed(val code: Int, val message: String) : NextcloudPutResult
}

class NextcloudHttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * Plain WebDAV client for one Nextcloud account (dev_plan.md §10.1/§10.2): `PROPFIND` to poll an
 * ETag without downloading, `GET` to download, `PUT` with `If-Match`/`If-None-Match` for the
 * optimistic-concurrency write. Every behaviour here was checked live against a real Nextcloud
 * server first (not assumed from documentation): a stale `If-Match` returns `412` with the
 * server's current ETag already in the response header (so a retry needs no extra `PROPFIND`);
 * `GET` carries the ETag and an `OC-Checksum: SHA1:...` header; an unconditional `PUT` silently
 * overwrites, which is exactly why every write here always sends one of the two conditions —
 * never a bare `PUT`; `If-None-Match: *` on an existing file returns `412`, which is what
 * [putIfAbsent] relies on for FR-S1's "never silently clobber a file already there."
 */
class NextcloudClient(
    private val server: String,
    private val loginName: String,
    appPassword: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val davBase = "${server.trimEnd('/')}/remote.php/dav/files/$loginName"
    private val auth = Credentials.basic(loginName, appPassword)

    private fun request(path: String) = Request.Builder().url(davBase + normalizePath(path)).header("Authorization", auth)

    /** `PROPFIND Depth: 0` — just the ETag/size, for the §10.2 polling loop. `null` if the path doesn't exist. */
    fun stat(path: String): NextcloudFileInfo? {
        val req = request(path).method("PROPFIND", PROPFIND_BASIC_BODY).header("Depth", "0").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (resp.code != 207) throw NextcloudHttpException(resp.code, resp.body?.string().orEmpty())
            val doc = parseXml(resp.body!!.string())
            val etag = firstText(doc, "getetag")?.let(::unquote) ?: return null
            val size = firstText(doc, "getcontentlength")?.toLongOrNull() ?: 0
            return NextcloudFileInfo(etag, size)
        }
    }

    /** Downloads the file. `null` if it doesn't exist. */
    fun get(path: String): NextcloudFile? {
        val req = request(path).get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw NextcloudHttpException(resp.code, resp.body?.string().orEmpty())
            val etag = resp.header("ETag")?.let(::unquote) ?: throw NextcloudHttpException(resp.code, "no ETag in GET response")
            val checksum = resp.header("OC-Checksum")?.substringAfter("SHA1:", "")?.ifEmpty { null }
            return NextcloudFile(resp.body!!.bytes(), etag, checksum)
        }
    }

    /** `PUT` with `If-Match: <ifMatchEtag>` (§10.2's rebase step, the one normal write path). */
    fun put(path: String, bytes: ByteArray, ifMatchEtag: String): NextcloudPutResult =
        executePut(request(path).put(bytes.toRequestBody(OCTET_STREAM)).header("If-Match", quote(ifMatchEtag)))

    /** `PUT` with `If-None-Match: *` — the very first upload only (FR-S1 "Переместить базу в Nextcloud"). */
    fun putIfAbsent(path: String, bytes: ByteArray): NextcloudPutResult =
        executePut(request(path).put(bytes.toRequestBody(OCTET_STREAM)).header("If-None-Match", "*"))

    private fun executePut(builder: Request.Builder): NextcloudPutResult {
        http.newCall(builder.build()).execute().use { resp ->
            return when {
                resp.code == 412 -> NextcloudPutResult.PreconditionFailed(resp.header("ETag")?.let(::unquote))
                resp.isSuccessful -> NextcloudPutResult.Applied(
                    resp.header("ETag")?.let(::unquote) ?: throw NextcloudHttpException(resp.code, "no ETag in PUT response"),
                )
                else -> NextcloudPutResult.Failed(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /** `PROPFIND Depth: 1` — the folder's immediate children, for a file picker (FR-S1/FR-S2). */
    fun list(folderPath: String): List<NextcloudEntry> {
        val req = request(folderPath).method("PROPFIND", PROPFIND_BASIC_BODY).header("Depth", "1").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw NextcloudHttpException(resp.code, resp.body?.string().orEmpty())
            val doc = parseXml(resp.body!!.string())
            val responses = doc.getElementsByTagNameNS("*", "response")
            val selfPath = folderPath.trim('/')
            val entries = ArrayList<NextcloudEntry>()
            for (i in 0 until responses.length) {
                val node = responses.item(i) as Element
                val href = firstText(node, "href") ?: continue
                // No leading slash, matching every other path in this client's API (stat/get/put/putIfAbsent).
                val relPath = URLDecoder.decode(href, Charsets.UTF_8).substringAfter("/remote.php/dav/files/$loginName").trim('/')
                if (relPath == selfPath) continue // the folder itself, not a child
                val isFolder = node.getElementsByTagNameNS("*", "collection").length > 0
                val etag = firstText(node, "getetag")?.let(::unquote)
                val size = firstText(node, "getcontentlength")?.toLongOrNull()
                entries += NextcloudEntry(relPath, isFolder, etag, size)
            }
            return entries
        }
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val PROPFIND_BASIC_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""
            .toRequestBody(XML_MEDIA_TYPE)

        private fun normalizePath(path: String): String = if (path.startsWith("/")) path else "/$path"

        private fun unquote(etag: String): String = etag.trim().removePrefix("W/").removePrefix("\"").removeSuffix("\"")
        private fun quote(etag: String): String = "\"${etag.trim().removePrefix("\"").removeSuffix("\"")}\""

        private fun parseXml(text: String): Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            // Untrusted server-returned XML: no external entities, same posture as any XML parser fed network input.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            return factory.newDocumentBuilder().parse(text.byteInputStream())
        }

        /** First `*:localName` text content under [scope] — tolerant of whatever prefix a server binds to `DAV:`/`oc:`. */
        private fun firstText(scope: Node, localName: String): String? {
            val list = when (scope) {
                is Document -> scope.getElementsByTagNameNS("*", localName)
                is Element -> scope.getElementsByTagNameNS("*", localName)
                else -> return null
            }
            return if (list.length > 0) (list.item(0) as Element).textContent else null
        }
    }
}
