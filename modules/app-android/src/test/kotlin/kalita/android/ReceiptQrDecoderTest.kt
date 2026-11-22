// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReceiptQrDecoderTest {
    @Test
    fun `decodes synthetic qr and handles blank image`() {
        val text = "https://suf.purs.gov.rs/verify?synthetic=1"
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 256, 256)
        val pixels = ByteArray(256 * 256) { i -> if (matrix[i % 256, i / 256]) 0 else 255.toByte() }
        ReceiptQrDecoder.decodeRotations(pixels, 256, 256) shouldBe text
        ReceiptQrDecoder.decodeRotations(ByteArray(256 * 256) { 255.toByte() }, 256, 256) shouldBe null
    }
}