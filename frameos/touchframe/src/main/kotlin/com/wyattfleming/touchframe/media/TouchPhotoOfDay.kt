package com.wyattfleming.touchframe.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Home's photo of the day comes from the JPEGs the slideshow already saved on the frame, never
 * from a second web page: on the 976 MB frame the browser is what the low-memory killer takes.
 * One photo per calendar day, decoded once, sampled down to the panel and stored as RGB_565.
 */
internal object TouchPhotoOfDay {
    /** The same photo all day, a different one tomorrow, independent of the order files were saved in. */
    fun pick(assetIds: List<String>, epochDay: Long): String? {
        if (assetIds.isEmpty()) return null
        val sorted = assetIds.distinct().sorted()
        return sorted[epochDay.mod(sorted.size.toLong()).toInt()]
    }

    /** Largest power-of-two sample that still covers the panel, so the decode never allocates the full photo. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    /** Null when the file is missing or not a decodable image; never throws out of memory. */
    fun decode(file: File, targetWidth: Int, targetHeight: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
