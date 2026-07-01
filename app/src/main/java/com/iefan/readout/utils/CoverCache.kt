package com.iefan.readout.utils

import androidx.collection.LruCache
import androidx.compose.ui.graphics.ImageBitmap

object CoverCache {
    // Cache up to 60 decoded cover images in memory
    private val cache = LruCache<String, ImageBitmap>(60)

    fun get(path: String): ImageBitmap? {
        return cache.get(path)
    }

    fun put(path: String, bitmap: ImageBitmap) {
        cache.put(path, bitmap)
    }

    fun remove(path: String) {
        cache.remove(path)
    }

    fun clear() {
        cache.evictAll()
    }
}
