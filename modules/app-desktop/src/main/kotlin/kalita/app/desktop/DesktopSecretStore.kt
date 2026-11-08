// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import kalita.platform.NoopSecretStore
import kalita.platform.SecretStore

/**
 * [SecretStore] for Windows/Linux (dev_plan.md §1.1 "Сеть": the only two desktop platforms this
 * project targets) — picks [WindowsCredentialManagerStore] or [LinuxSecretServiceStore] by
 * `os.name` once, at object-init time; an unrecognized OS (there is none in scope today, but a
 * future macOS build is not impossible) falls back to [NoopSecretStore] rather than guessing at
 * an implementation nothing has verified there.
 *
 * Lives in `app-desktop`, not the shared `platform` module: its two real implementations pull in
 * JNA and a D-Bus client (see their own doc comments) that `app-android` has no business bundling
 * into the APK just because it also depends on `platform` for the bare [SecretStore] interface —
 * found the hard way, as a `mergeDebugJavaResource` `META-INF/AL2.0` duplicate-file failure from
 * `jna`/`jna-platform` leaking into Android's packaging when these lived in `platform` itself.
 */
object DesktopSecretStore : SecretStore {
    private val delegate: SecretStore = secretStoreFor(System.getProperty("os.name", ""))

    override fun get(key: String): String? = delegate.get(key)
    override fun put(key: String, value: String) = delegate.put(key, value)
    override fun remove(key: String) = delegate.remove(key)
}

/** Pulled out of [DesktopSecretStore]'s init so the OS→implementation mapping is testable without
 * depending on the JVM actually running under each OS. */
internal fun secretStoreFor(osName: String): SecretStore = when {
    osName.startsWith("Windows", ignoreCase = true) -> WindowsCredentialManagerStore
    osName.contains("Linux", ignoreCase = true) -> LinuxSecretServiceStore
    else -> NoopSecretStore
}
