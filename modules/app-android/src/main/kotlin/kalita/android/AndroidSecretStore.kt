// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kalita.platform.SecretStore

/**
 * Android half of [SecretStore] (dev_plan.md NFR-8: "Android Keystore") — `EncryptedSharedPreferences`
 * backed by a Keystore-held `MasterKey`, the standard Jetpack way to persist a secret on Android;
 * lives here rather than inside the `platform` module itself, same reasoning as `platform`'s own
 * `DesktopSecretStore.kt` comment: `platform` has no Android source set to put an `androidx` import
 * in, and `app-android` already is where every other one in this project lives (§9.3's own rule
 * confining `android` / `androidx` imports to `app-android` and "Android-реализациях platform").
 *
 * Construction touches the Keystore, so it stays out of [AndroidAppController]'s constructor
 * (which takes no [Context] at all, on purpose — see its own doc comment on plain-JVM
 * testability); [MainActivity] builds one real instance and hands it in.
 */
class AndroidSecretStore(context: Context) : SecretStore {
    // Built eagerly but defensively: a broken/absent Keystore on some device would otherwise throw
    // out of this very constructor, crashing whatever creates it — [SecretStore]'s contract is
    // "never throws", including this edge, same as the desktop implementations' own try/catch.
    private val prefs: SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            context,
            "kalita_secrets",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    override fun get(key: String): String? = try {
        prefs?.getString(key, null)
    } catch (e: Exception) {
        null
    }

    override fun put(key: String, value: String) {
        try {
            prefs?.edit { putString(key, value) }
        } catch (e: Exception) {
            // best effort, per SecretStore's contract
        }
    }

    override fun remove(key: String) {
        try {
            prefs?.edit { remove(key) }
        } catch (e: Exception) {
            // best effort, per SecretStore's contract
        }
    }
}
