package com.iefan.readout.utils

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

object CoverCache {
    // Byte-based LRU: evicts by actual bitmap size, not item count.
    // 20 MB cap is safe on all target devices (>= 2 GB RAM).
    // With inSampleSize-decoded thumbnails (~200 KB each), this holds ~100 covers.
    private const val MAX_CACHE_BYTES = 20 * 1024 * 1024 // 20 MB

    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            // ImageBitmap exposes width/height; assume ARGB_8888 (4 bytes/pixel).
            // After RGB_565 decode it's 2 bytes/pixel, so this is a conservative over-estimate
            // that keeps the cache even more conservative — which is what we want.
            return value.width * value.height * 4
        }
    }
    private val failedPaths = java.util.Collections.synchronizedSet(HashSet<String>())

    fun get(path: String): ImageBitmap? = cache.get(path)

    fun put(path: String, bitmap: ImageBitmap) {
        cache.put(path, bitmap)
        failedPaths.remove(path)
    }

    fun isFailed(path: String): Boolean = failedPaths.contains(path)

    fun markFailed(path: String) {
        failedPaths.add(path)
    }

    fun remove(path: String) {
        cache.remove(path)
        failedPaths.remove(path)
    }

    fun clear() {
        cache.evictAll()
        failedPaths.clear()
    }
}
