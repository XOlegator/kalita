// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSecretStoreTest {
    @Test
    @Suppress("DEPRECATION") // Create credentials in the format used by previous APKs.
    fun migratesAndPersistsCredentials() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-secrets-${UUID.randomUUID()}"
        val directory = File(app.noBackupFilesDir, namespace)
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(namespace, mode)
        }
        try {
            val legacy = EncryptedSharedPreferences.create(
                context,
                "kalita_secrets",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            assertTrue(legacy.edit().putString("nextcloud", "старый пароль 🔑").putString("vision", "").commit())

            val store = AndroidSecretStore(context)
            assertEquals("старый пароль 🔑", store.get("nextcloud"))
            assertEquals("", store.get("vision"))
            assertTrue(context.getSharedPreferences("kalita_secrets", Context.MODE_PRIVATE).all.isEmpty())
            store.put("nextcloud", "new password")
            store.remove("vision")

            val reopened = AndroidSecretStore(context)
            assertEquals("new password", reopened.get("nextcloud"))
            assertNull(reopened.get("vision"))
        } finally {
            app.deleteSharedPreferences(namespace)
            directory.deleteRecursively()
        }
    }
}
