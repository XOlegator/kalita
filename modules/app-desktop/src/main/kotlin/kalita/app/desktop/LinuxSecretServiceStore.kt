// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.platform.SecretStore
import org.freedesktop.secret.simple.SimpleCollection

/**
 * Linux half of [SecretStore] (dev_plan.md NFR-8: "Secret Service/libsecret") — talks the
 * `org.freedesktop.Secret` D-Bus protocol directly via `de.swiesend:secret-service`, the same
 * protocol GNOME Keyring and KWallet both implement, without shelling out to `secret-tool` (not
 * guaranteed installed — only the shared library usually is) or a heavier multi-OS wrapper library
 * (see `libs.versions.toml`'s comment on `secretService` for why not `java-keyring`).
 *
 * Every item is scoped by two D-Bus attributes, `service` (always [SERVICE]) and `key` (the
 * caller's own key) — a lookup here can never return, update, or delete another application's
 * item in the same default collection, only ever Kalita's own.
 */
internal object LinuxSecretServiceStore : SecretStore {
    private const val SERVICE = "kalita"
    private const val LABEL = "Kalita"

    override fun get(key: String): String? = withCollection { collection ->
        val path = findItem(collection, key) ?: return@withCollection null
        collection.getSecret(path)?.let { String(it) }
    }

    override fun put(key: String, value: String) {
        withCollection { collection ->
            val existing = findItem(collection, key)
            if (existing != null) {
                collection.updateItem(existing, LABEL, value, attributes(key))
            } else {
                collection.createItem(LABEL, value, attributes(key))
            }
        }
    }

    override fun remove(key: String) {
        withCollection { collection ->
            val items: List<String>? = collection.getItems(attributes(key))
            if (!items.isNullOrEmpty()) collection.deleteItems(items)
        }
    }

    /** `SimpleCollection.getItems` answers a plain Java `List<String>` with no `@Nullable`
     * annotation, but a real KWallet-backed default collection was observed (live, on this
     * project's own desktop) to return a genuine `null` rather than an empty list when nothing
     * matches — handled explicitly here rather than trusting the signature's apparent non-null. */
    private fun findItem(collection: SimpleCollection, key: String): String? {
        val items: List<String>? = collection.getItems(attributes(key))
        return items?.firstOrNull()
    }

    private fun attributes(key: String): Map<String, String> = mapOf("service" to SERVICE, "key" to key)

    /** One connection per call, not held open — this runs at most a few times per app lifetime
     * (login, a handful of lookups), not a hot path worth pooling. "No Secret Service reachable"
     * (headless, no session bus, locked with no prompt agent, …) can surface two different ways,
     * both caught here like everything else [SecretStore] promises never to throw: an
     * [java.io.IOException] from [SimpleCollection]'s own constructor on a later call, but on the
     * *first* call in the process, [SimpleCollection]'s static initializer itself eagerly tries to
     * resolve the D-Bus connection and throws [ExceptionInInitializerError] instead — found live,
     * on a headless CI runner with no `DISPLAY`/session bus at all, not assumed from the library's
     * own documentation. [ExceptionInInitializerError] is an [Error], not an [Exception] — `catch
     * (e: Exception)` alone would miss it and crash the caller, the opposite of this method's job. */
    private fun <T> withCollection(block: (SimpleCollection) -> T): T? =
        try {
            SimpleCollection().use(block)
        } catch (e: Throwable) {
            null
        }
}
