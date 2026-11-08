// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Windows half of [SecretStore] (dev_plan.md NFR-8: "Windows Credential Manager") — jna-platform
 * (already in `libs.versions.toml` for the unrelated Tess4J/OCR use §13.1 pre-approved) declares
 * no `CredRead`/`CredWrite`/`CredDelete` bindings (checked the actual source, not assumed), so
 * this declares the `Advapi32` generic-credential functions directly — the standard, well-worn way
 * to reach this API from JNA; no narrower ready-made library exists for just this.
 *
 * Every credential is stored under `kalita/<key>` as `CRED_TYPE_GENERIC`, `CRED_PERSIST_LOCAL_MACHINE`
 * (survives reboots, stays on this machine) — distinct from anything a browser or another app
 * stores, by the `kalita/` prefix alone.
 */
internal object WindowsCredentialManagerStore : SecretStore {
    private const val TARGET_PREFIX = "kalita/"
    private const val CRED_TYPE_GENERIC = 1
    private const val CRED_PERSIST_LOCAL_MACHINE = 2

    @Structure.FieldOrder("dwLowDateTime", "dwHighDateTime")
    internal class FILETIME : Structure() {
        @JvmField var dwLowDateTime: Int = 0
        @JvmField var dwHighDateTime: Int = 0
    }

    @Structure.FieldOrder(
        "flags", "type", "targetName", "comment", "lastWritten", "credentialBlobSize",
        "credentialBlob", "persist", "attributeCount", "attributes", "targetAlias", "userName",
    )
    internal class CREDENTIAL(p: Pointer? = null) : Structure(p) {
        @JvmField var flags: Int = 0
        @JvmField var type: Int = 0
        @JvmField var targetName: WString? = null
        @JvmField var comment: WString? = null
        @JvmField var lastWritten: FILETIME = FILETIME()
        @JvmField var credentialBlobSize: Int = 0
        @JvmField var credentialBlob: Pointer? = null
        @JvmField var persist: Int = 0
        @JvmField var attributeCount: Int = 0
        @JvmField var attributes: Pointer? = null
        @JvmField var targetAlias: WString? = null
        @JvmField var userName: WString? = null
    }

    /** Declared without the `W` suffix on purpose — [W32APIOptions.DEFAULT_OPTIONS]'s function
     * mapper resolves each to its wide/Unicode variant (`CredWriteW`, …), the same idiom
     * jna-platform's own `Advapi32` interface uses for every other Win32 function. */
    private interface Advapi32Ext : StdCallLibrary {
        fun CredWrite(credential: CREDENTIAL, flags: Int): Boolean
        fun CredRead(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
        fun CredFree(buffer: Pointer)
        fun CredDelete(targetName: WString, type: Int, flags: Int): Boolean
    }

    private val lib: Advapi32Ext? = try {
        Native.load("Advapi32", Advapi32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS)
    } catch (e: Throwable) {
        null
    }

    override fun get(key: String): String? {
        val advapi = lib ?: return null
        val out = PointerByReference()
        return try {
            if (!advapi.CredRead(WString(TARGET_PREFIX + key), CRED_TYPE_GENERIC, 0, out)) return null
            val credPtr = out.value ?: return null
            try {
                val cred = CREDENTIAL(credPtr)
                cred.read()
                val blob = cred.credentialBlob ?: return null
                String(blob.getByteArray(0, cred.credentialBlobSize), Charsets.UTF_16LE)
            } finally {
                advapi.CredFree(credPtr)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun put(key: String, value: String) {
        val advapi = lib ?: return
        try {
            val blobBytes = value.toByteArray(Charsets.UTF_16LE)
            val blob = Memory(blobBytes.size.toLong().coerceAtLeast(1))
            blob.write(0, blobBytes, 0, blobBytes.size)
            val cred = CREDENTIAL()
            cred.type = CRED_TYPE_GENERIC
            cred.targetName = WString(TARGET_PREFIX + key)
            cred.credentialBlobSize = blobBytes.size
            cred.credentialBlob = blob
            cred.persist = CRED_PERSIST_LOCAL_MACHINE
            advapi.CredWrite(cred, 0)
        } catch (e: Exception) {
            // best effort, per SecretStore's contract
        }
    }

    override fun remove(key: String) {
        val advapi = lib ?: return
        try {
            advapi.CredDelete(WString(TARGET_PREFIX + key), CRED_TYPE_GENERIC, 0)
        } catch (e: Exception) {
            // best effort, per SecretStore's contract
        }
    }
}
