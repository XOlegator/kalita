// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.IOException
import kotlin.math.max

/** Decodes a QR from a photo URI, or returns null if none is found. Blocking; call off the main thread.
 *  Unreadable or invalid images throw an exception. The caller retains ownership of the URI. */
object ReceiptQrDecoder {
    private const val MAX_SIDE = 2048

    fun decode(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw IOException("Cannot open image")).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid image")
        var sample = 1
        while ((max(bounds.outWidth, bounds.outHeight).toLong() + sample - 1) / sample > MAX_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = (resolver.openInputStream(uri) ?: throw IOException("Cannot open image")).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Cannot decode image")
        val width = bitmap.width
        val height = bitmap.height
        val luminance = try {
            if (width > MAX_SIDE || height > MAX_SIDE) throw IOException("Decoded image is too large")
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            RGBLuminanceSource(width, height, pixels).matrix
        } finally {
            bitmap.recycle()
        }
        return decodeRotations(luminance, width, height)
    }

    internal fun decodeRotations(data: ByteArray, initialWidth: Int, initialHeight: Int): String? {
        var current = data
        var rotated = ByteArray(data.size)
        var width = initialWidth
        var height = initialHeight
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        repeat(4) { turn ->
            val source = PlanarYUVLuminanceSource(current, width, height, 0, 0, width, height, false)
            try {
                return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: ReaderException) {
                // Try the next orientation.
            }
            if (turn < 3) {
                for (y in 0 until height) {
                    for (x in 0 until width) rotated[x * height + height - 1 - y] = current[y * width + x]
                }
                val previous = current
                current = rotated
                rotated = previous
                val previousWidth = width
                width = height
                height = previousWidth
            }
        }
        return null
    }
}