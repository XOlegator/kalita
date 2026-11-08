// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.platform

/**
 * OS-level credential storage (dev_plan.md NFR-8, §9.4: "Пароли, токены — хранилище ОС") — the
 * Nextcloud app password behind FR-S1/FR-S2 is the first thing this backs. [key] is caller-chosen
 * and opaque to the store (e.g. `"nextcloud|<server>|<loginName>"`); every implementation here
 * scopes its own storage by an app-specific service name first, so a lookup here never returns a
 * secret some other application stored under a key that happens to collide.
 *
 * [get] returns `null` both when [key] has nothing stored under it and when the store itself isn't
 * reachable right now (locked, no session bus, OS API unavailable, …) — a caller already has to
 * fall back to asking the user again either way, so the two cases don't need telling apart. [put]
 * and [remove] likewise never throw: a store that can't persist right now just means the next
 * restart asks again, same as before this existed, not a crash.
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Used wherever no OS secret store is reachable — same end result as before `SecretStore`
 * existed (nothing persists across a restart), just named instead of silently absent. */
object NoopSecretStore : SecretStore {
    override fun get(key: String): String? = null
    override fun put(key: String, value: String) = Unit
    override fun remove(key: String) = Unit
}
