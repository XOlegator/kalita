// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** An in-memory [PluginHost] for tests — no real network, no OS secret store, a fixed clock. */
class FakePluginHost(fixedInstant: Instant = Instant.parse("2026-11-09T00:00:00Z")) : PluginHost {
    val requests = mutableListOf<String>()
    private val secrets = mutableMapOf<String, String>()
    val logged = mutableListOf<Pair<PluginLogLevel, String>>()

    override val httpClient = object : PluginHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): String {
            requests += url
            return "{}"
        }

        override suspend fun post(url: String, body: String, headers: Map<String, String>): String {
            requests += url
            return "{}"
        }
    }

    override val secretStore = object : PluginSecretStore {
        override fun get(key: String): String? = secrets[key]

        override fun put(key: String, value: String) {
            secrets[key] = value
        }

        override fun remove(key: String) {
            secrets.remove(key)
        }
    }

    override val logger = PluginLogger { level, message -> logged += level to message }

    override val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
}
