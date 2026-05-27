package com.example.bleapp.ble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

object BleImageCompressor {

    private const val MAX_DIMENSION = 720
    private const val INITIAL_QUALITY = 82
    private const val MIN_QUALITY = 45
    private const val MAX_JPEG_BYTES = 96_000

    fun compress(context: Context, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: error("Could not open image")

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: error("Could not decode image")

        return try {
            compressBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        var quality = INITIAL_QUALITY
        var best: ByteArray? = null
        while (quality >= MIN_QUALITY) {
            val bytes = bitmap.toJpeg(quality)
            best = bytes
            if (bytes.size <= MAX_JPEG_BYTES) return bytes
            quality -= 8
        }
        return best ?: error("Could not compress image")
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxDimension || halfHeight / sample >= maxDimension) {
            sample *= 2
        }
        return max(1, sample)
    }
}
