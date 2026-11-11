// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import java.time.Clock

/**
 * Minimal HTTP contract the host hands to a plugin (dev_plan.md §9.11: "Хост предоставляет
 * плагину сервисы: HTTP-клиент ..., плагины не тянут свои сетевые стеки"). Deliberately not the
 * shape of any particular HTTP library's client — a plugin (and this module) must not depend on
 * `okhttp` or similar directly, only on this contract; the host picks the implementation.
 */
interface PluginHttpClient {
    /** Throws on a non-2xx response or a transport failure; plugins see one failure shape, not a library's. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String
}

/**
 * Per-plugin secret storage (dev_plan.md §9.11, NFR-8 "Пароли, токены — хранилище ОС"). Not the
 * same type as `platform`'s own `SecretStore` — plugin-api has no dependency on `platform`
 * (dev_plan.md §9.3); the host implementation adapts whichever OS-backed store it already has
 * to this contract, namespaced per [PluginDescriptor.id] so plugins cannot read each other's
 * secrets.
 */
interface PluginSecretStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/** A line a plugin wants in the host's log, at the level the plugin judges it. */
enum class PluginLogLevel { Debug, Info, Warn, Error }

fun interface PluginLogger {
    fun log(level: PluginLogLevel, message: String)
}

/**
 * Services the host injects into a plugin at [Plugin.initialize] (dev_plan.md §9.11). A plugin
 * reaches the network, secrets, logging and the current time only through this — never by
 * opening its own `HttpClient`, reading `System.getenv` for keys, or calling
 * `Clock.systemUTC()`/`Instant.now()` directly, so host-side network permission, secret scoping
 * and deterministic tests all stay possible.
 */
interface PluginHost {
    val httpClient: PluginHttpClient
    val secretStore: PluginSecretStore
    val logger: PluginLogger
    val clock: Clock

    /**
     * The database open right now, or `null` when there is none. Looked up on every call rather
     * than captured at [Plugin.initialize]: a plugin outlives any one database — the user opens
     * and closes files while the plugin stays loaded.
     */
    fun database(): DatabaseContext? = null
}
