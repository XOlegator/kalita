// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import kalita.storage.api.ChangeSet
import kalita.storage.api.SubmitResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Local-socket channel to a running desktop instance (dev_plan.md §12.15, FR-AG6): lets the
 * `kalita` CLI / `kalita mcp` submit a [ChangeSet] through whichever process already holds
 * [DatabaseLock]'s write lock, instead of simply failing read-only. Reads need no channel — any
 * process reads the Jet file directly ([LocalDatabase.open] always does, lock or no lock) — only
 * [kalita.storage.api.StorageSession.submit] is exclusive.
 *
 * Built on `java.nio`'s cross-platform Unix-domain-socket support (`StandardProtocolFamily.UNIX`,
 * JDK 16+), which OpenJDK backs by `AF_UNIX` on both target OSes (NFR-2: Windows 11, Linux — AF_UNIX
 * has existed on Windows since version 1803/Server 2019), so one implementation covers both without
 * a separate named-pipe path. Not been verified by hand on a genuinely old Windows without `AF_UNIX`
 * — [AgentChannelServer.start]/[AgentChannelClient.trySubmit] simply come back `null` there, so the
 * absence only loses the live-forwarding convenience, never correctness (the caller falls back to a
 * normal local open, same as before this channel existed).
 */
object AgentChannel {
    /**
     * One socket per database, named by a hash of its canonical path rather than placed next to the
     * (possibly Nextcloud-synced, §10) `.mdb`/its `.kalita-data` folder — a socket file has no
     * business inside a folder a sync client walks — and short enough to dodge the usual
     * `sun_path`-length limits on Unix domain socket paths.
     */
    fun socketPath(dbFile: File): Path {
        val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.io.tmpdir")
        val digest = MessageDigest.getInstance("SHA-256").digest(dbFile.absoluteFile.canonicalPath.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(24)
        return File(runtimeDir, "kalita").toPath().resolve("$name.sock")
    }

    internal fun readLine(channel: SocketChannel): String? = Channels.newReader(channel, Charsets.UTF_8).buffered().readLine()

    internal fun writeLine(channel: SocketChannel, line: String) {
        val writer = Channels.newWriter(channel, Charsets.UTF_8)
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }
}

/**
 * Server half: one per open database, run by whichever process holds the write lock (normally the
 * desktop app — [kalita.app.desktop.AppController], not imported from here, same layering as
 * [kalita.agent.api.AgentSession] not being imported by `AgentApiModule`'s own deferred-scope note).
 * [handler] is that process's own change-submission path, so a remotely-submitted change set updates
 * the exact same in-memory state a locally-made edit would — [handler] runs on a connection thread,
 * never the caller's own thread, so it must be safe to call from somewhere other than a UI thread.
 */
class AgentChannelServer private constructor(private val serverChannel: ServerSocketChannel, private val path: Path) : AutoCloseable {
    @Volatile private var closed = false

    private fun run(handler: (ChangeSet) -> SubmitResult) {
        while (!closed) {
            val client = try {
                serverChannel.accept()
            } catch (e: IOException) {
                if (closed) return else continue
            }
            thread(isDaemon = true, name = "kalita-agent-channel-conn") { serve(client, handler) }
        }
    }

    private fun serve(client: SocketChannel, handler: (ChangeSet) -> SubmitResult) {
        client.use {
            val result = try {
                val line = AgentChannel.readLine(it) ?: return
                val changeSet = ChangeSetJson.decode(Json.parseToJsonElement(line).jsonObject)
                handler(changeSet)
            } catch (e: Exception) {
                SubmitResult.Failed("agent channel request error: ${e.message}")
            }
            runCatching { AgentChannel.writeLine(it, SubmitResultJson.encode(result).toString()) }
        }
    }

    override fun close() {
        closed = true
        runCatching { serverChannel.close() }
        runCatching { Files.deleteIfExists(path) }
    }

    companion object {
        /**
         * Starts listening for [dbFile], or returns `null` if a local channel is not available on
         * this platform/JDK (see [AgentChannel]'s KDoc) — never throws. Only the single process that
         * already holds [DatabaseLock]'s write lock for [dbFile] may call this, so a leftover socket
         * file at the same path can only be from a previous run of *this* process that did not shut
         * down cleanly, same reasoning [DatabaseLock] itself documents for a stale `.ldb` — safe to
         * delete and rebind rather than treat as still-live.
         */
        fun start(dbFile: File, handler: (ChangeSet) -> SubmitResult): AgentChannelServer? {
            val path = AgentChannel.socketPath(dbFile)
            return try {
                Files.createDirectories(path.parent)
                runCatching { Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwx------")) }
                Files.deleteIfExists(path)
                val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                channel.bind(UnixDomainSocketAddress.of(path))
                val server = AgentChannelServer(channel, path)
                thread(isDaemon = true, name = "kalita-agent-channel") { server.run(handler) }
                server
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Client half, used by [kalita.agent.api.AgentSession] (not imported from here, same layering
 * reasoning as [AgentChannelServer]'s own KDoc).
 */
object AgentChannelClient {
    /**
     * `null` means the channel is not available — no running desktop instance for [dbFile], or an
     * unsupported platform — and the caller should fall back to its own local
     * [kalita.storage.api.StorageSession.submit]. A non-null result is authoritative: it already
     * reflects whatever the desktop instance's own conflict detection (§9.5/§10.5) decided, against
     * *its* current snapshot, not the caller's possibly-stale one — exactly the same base-value check
     * [kalita.storage.api.ChangeSetBuilder] already relies on for any other conflicting submit.
     */
    fun trySubmit(dbFile: File, changeSet: ChangeSet): SubmitResult? {
        val path = AgentChannel.socketPath(dbFile)
        if (!Files.exists(path)) return null
        return try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).use { channel ->
                AgentChannel.writeLine(channel, ChangeSetJson.encode(changeSet).toString())
                val line = AgentChannel.readLine(channel) ?: return null
                SubmitResultJson.decode(Json.parseToJsonElement(line).jsonObject)
            }
        } catch (e: IOException) {
            null
        }
    }
}
