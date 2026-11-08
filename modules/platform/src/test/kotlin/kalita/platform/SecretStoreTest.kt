// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.platform

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [secretStoreFor] (the OS→implementation mapping behind [DesktopSecretStore]) and [NoopSecretStore].
 *
 * [LinuxSecretServiceStore]/[WindowsCredentialManagerStore] themselves talk to a real OS secret
 * store (Secret Service over D-Bus, Win32 Credential Manager) — not something a hosted CI runner
 * reliably has (GitHub's `ubuntu-latest` has no desktop session or keyring daemon running), so
 * they aren't exercised here. Verified live instead, once, by hand against this project's own
 * desktop session (KWallet-backed Secret Service) — see dev_plan.md's Этап 9 срез for that run.
 */
class SecretStoreTest {
    @Test
    fun `Windows in the OS name selects the Credential Manager store`() {
        secretStoreFor("Windows 11") shouldBe WindowsCredentialManagerStore
        secretStoreFor("windows server 2025") shouldBe WindowsCredentialManagerStore
    }

    @Test
    fun `Linux in the OS name selects the Secret Service store`() {
        secretStoreFor("Linux") shouldBe LinuxSecretServiceStore
        secretStoreFor("Linux 6.17.0") shouldBe LinuxSecretServiceStore
    }

    @Test
    fun `an unrecognized OS name falls back to the no-op store`() {
        secretStoreFor("Mac OS X") shouldBe NoopSecretStore
        secretStoreFor("") shouldBe NoopSecretStore
    }

    @Test
    fun `NoopSecretStore never persists anything, and never throws`() {
        NoopSecretStore.put("key", "value")
        NoopSecretStore.get("key") shouldBe null
        NoopSecretStore.remove("key")
    }
}
