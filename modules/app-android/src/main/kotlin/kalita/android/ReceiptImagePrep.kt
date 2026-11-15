// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kalita.agent.api.ReceiptImage
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Scales a phone photo down to what a vision model needs and what is cheap to upload: the long side is
 * capped (a long receipt keeps a higher cap so small print stays readable), the EXIF rotation is applied,
 * and the result is a JPEG. Blocking — call it off the main thread.
 */
object ReceiptImagePrep {
    private const val NORMAL_MAX_SIDE = 2000
    private const val LONG_RECEIPT_MAX_SIDE = 3000

    fun prepare(context: Context, uri: Uri): ReceiptImage {
        val bitmap = decode(context, uri, LONG_RECEIPT_MAX_SIDE) ?: error("Не удалось прочитать снимок")
        val rotated = rotate(bitmap, exifRotation(context, uri))
        val longSide = max(rotated.width, rotated.height)
        val shortSide = minOf(rotated.width, rotated.height)
        val cap = if (longSide > shortSide * 2) LONG_RECEIPT_MAX_SIDE else NORMAL_MAX_SIDE
        val scaled = if (longSide > cap) {
            val k = cap.toFloat() / longSide
            Bitmap.createScaledBitmap(rotated, (rotated.width * k).toInt().coerceAtLeast(1), (rotated.height * k).toInt().coerceAtLeast(1), true)
        } else rotated
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return ReceiptImage(out.toByteArray(), "image/jpeg")
    }

    /** A small preview for the list of frames. */
    fun thumbnail(context: Context, uri: Uri, maxSide: Int = 320): Bitmap? =
        decode(context, uri, maxSide)?.let { rotate(it, exifRotation(context, uri)) }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun exifRotation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    }.getOrNull() ?: 0

    private fun rotate(b: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
