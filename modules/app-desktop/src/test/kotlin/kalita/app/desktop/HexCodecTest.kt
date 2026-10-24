// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HexCodecTest {
    @Test
    fun `encodes a known byte array to lowercase hex`() {
        byteArrayOf(0x00, 0x1a, 0xff.toByte()).toHeaderHex() shouldBe "001aff"
    }

    @Test
    fun `empty array encodes to an empty string`() {
        ByteArray(0).toHeaderHex() shouldBe ""
    }

    @Test
    fun `round-trips an arbitrary byte array through hex`() {
        val bytes = Random(42).nextBytes(200)
        bytes.toHeaderHex().fromHeaderHex().contentEquals(bytes) shouldBe true
    }

    @Test
    fun `rejects an odd-length hex string`() {
        runCatching { "abc".fromHeaderHex() }.isFailure shouldBe true
    }
}
