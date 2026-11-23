// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kalita.platform.SecretStore
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps credential names and values in an encrypted, device-local file. */
class AndroidSecretStore(private val context: Context) : SecretStore {
    override fun get(key: String): String? = access { file ->
        read(file).opt(key) as? String
    }

    override fun put(key: String, value: String) {
        access { file -> write(file, read(file).put(key, value)) }
    }

    override fun remove(key: String) {
        access { file ->
            val secrets = read(file)
            if (secrets.remove(key) != null) write(file, secrets)
        }
    }

    private fun <T> access(block: (AtomicFile) -> T): T? = synchronized(lock) {
        try {
            block(AtomicFile(File(context.noBackupFilesDir, FILE_NAME)))
        } catch (_: Exception) {
            null // Keystore and disk access are best effort per SecretStore's contract.
        }
    }

    private fun read(file: AtomicFile): JSONObject {
        val encrypted = try {
            file.readFully()
        } catch (e: FileNotFoundException) {
            if (file.baseFile.exists()) throw e
            return migrate(file)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, encrypted, 0, IV_SIZE))
        return JSONObject(cipher.doFinal(encrypted, IV_SIZE, encrypted.size - IV_SIZE).decodeToString())
    }

    private fun write(file: AtomicFile, secrets: JSONObject) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        check(cipher.iv.size == IV_SIZE)
        val encrypted = cipher.iv + cipher.doFinal(secrets.toString().encodeToByteArray())
        val output = file.startWrite()
        try {
            output.write(encrypted)
            output.fd.sync()
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        check(file.readFully().contentEquals(encrypted))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    // The old API is needed only to decrypt credentials written by previous APKs.
    @Suppress("DEPRECATION")
    private fun migrate(file: AtomicFile): JSONObject {
        val old = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (old.all.isEmpty()) return JSONObject()

        val encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFS,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val secrets = JSONObject(encryptedPrefs.all)
        write(file, secrets)
        old.edit().clear().apply()
        return secrets
    }

    private companion object {
        val lock = Any()
        const val FILE_NAME = "kalita_secrets_v2"
        const val LEGACY_PREFS = "kalita_secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kalita_secrets_v2"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
