package com.iefan.readout.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object BitmapOptimizer {

    /**
     * Calculates the optimal power-of-2 inSampleSize so that decoded dimensions
     * are close to reqWidth and reqHeight, preventing excessive memory usage.
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // Keep halving while either dimension is significantly larger than target
            while ((halfHeight / inSampleSize) >= reqHeight || (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Decodes a sampled bitmap from a local file path using RGB_565.
     */
    fun decodeSampledBitmapFromFile(
        path: String,
        reqWidth: Int = 360,
        reqHeight: Int = 540
    ): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOpts, reqWidth, reqHeight)
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, decodeOpts)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Decodes a sampled bitmap from an Android content or file Uri using RGB_565.
     */
    fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int = 360,
        reqHeight: Int = 540
    ): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOpts, reqWidth, reqHeight)
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Decodes a sampled bitmap from raw ByteArray data (e.g. from zip entries).
     */
    fun decodeSampledBitmapFromByteArray(
        data: ByteArray,
        reqWidth: Int = 360,
        reqHeight: Int = 540
    ): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOpts, reqWidth, reqHeight)
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Saves an optimized, downsampled cover file to disk from a Uri.
     * Prevents storing 20+ MB raw camera images in internal storage.
     */
    fun saveOptimizedCover(
        context: Context,
        uri: Uri,
        targetFile: File,
        maxWidth: Int = 480,
        maxHeight: Int = 720
    ): Boolean {
        return try {
            val bitmap = decodeSampledBitmapFromUri(context, uri, maxWidth, maxHeight)
            if (bitmap != null) {
                FileOutputStream(targetFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                true
            } else {
                // Fallback to direct stream copy if decoding failed
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.exists() && targetFile.length() > 0
            }
        } catch (_: Throwable) {
            false
        }
    }
}
