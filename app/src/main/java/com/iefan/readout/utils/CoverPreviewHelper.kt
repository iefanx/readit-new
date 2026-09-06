package com.iefan.readout.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object CoverPreviewHelper {

    /**
     * Extracts or decodes a book cover preview as an ImageBitmap.
     * Supports:
     * 1. Direct custom cover image URI
     * 2. EPUB files (extracts cover from OPF manifest or known cover filenames)
     * 3. PDF files (renders page 0 via PdfRenderer)
     */
    suspend fun extractCoverPreview(
        context: Context,
        uri: Uri,
        fileName: String
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val lowerName = fileName.lowercase()
            val mimeType = context.contentResolver.getType(uri)?.lowercase() ?: ""

            val isPdf = lowerName.endsWith(".pdf") || mimeType.contains("pdf")
            val isEpub = lowerName.endsWith(".epub") || mimeType.contains("epub")
            val isImage = mimeType.startsWith("image/") ||
                    lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                    lowerName.endsWith(".png") || lowerName.endsWith(".webp")

            when {
                isImage -> decodeImageUri(context, uri)
                isPdf -> renderPdfCover(context, uri)
                isEpub -> extractEpubCover(context, uri)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Decodes a direct image URI with downsampling for preview size.
     */
    suspend fun decodeImageUri(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
        BitmapOptimizer.decodeSampledBitmapFromUri(context, uri, 360, 540)?.asImageBitmap()
    }

    /**
     * Renders page 0 of a PDF file using PdfRenderer.
     */
    private fun renderPdfCover(context: Context, uri: Uri): ImageBitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val targetWidth = 240
                val ratio = page.height.toFloat() / page.width.toFloat()
                val targetHeight = (targetWidth * ratio).toInt().coerceIn(180, 400)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap.asImageBitmap()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Fast extraction of EPUB cover image from ZIP entries.
     */
    private fun extractEpubCover(context: Context, uri: Uri): ImageBitmap? {
        val tempFile = File(context.cacheDir, "epub_preview_${System.currentTimeMillis()}.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            ZipFile(tempFile).use { zip ->
                val entries = zip.entries().toList()

                // 1. Try parsing OPF manifest for cover href
                val opfEntry = entries.firstOrNull { it.name.lowercase().endsWith(".opf") }
                if (opfEntry != null) {
                    val opfContent = zip.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).readText()
                    val opfDir = opfEntry.name.substringBeforeLast("/", "")

                    val coverHref =
                        Regex("""<item[^>]+id=["']cover[^"*]["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(opfContent)?.groupValues?.getOrNull(1)
                            ?: Regex("""<item[^>]+properties=["'][^']*cover-image[^'*]["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                .find(opfContent)?.groupValues?.getOrNull(1)
                            ?: Regex("""<item[^>]+href=["']([^"']+)["'][^>]+properties=["'][^']*cover-image[^'*]["']""", RegexOption.IGNORE_CASE)
                                .find(opfContent)?.groupValues?.getOrNull(1)

                    if (coverHref != null) {
                        val candidatePath = if (opfDir.isEmpty()) coverHref else "$opfDir/$coverHref"
                        val coverEntry = zip.getEntry(candidatePath)
                            ?: entries.firstOrNull { it.name.endsWith(coverHref, ignoreCase = true) }
                        if (coverEntry != null) {
                            zip.getInputStream(coverEntry).use { inp ->
                                val bmp = BitmapOptimizer.decodeSampledBitmapFromByteArray(inp.readBytes(), 360, 540)
                                if (bmp != null) return@extractEpubCover bmp.asImageBitmap()
                            }
                        }
                    }
                }

                // 2. Try well-known cover file names
                val knownNames = listOf(
                    "cover.jpg", "cover.jpeg", "cover.png",
                    "images/cover.jpg", "images/cover.jpeg", "images/cover.png",
                    "OEBPS/cover.jpg", "OEBPS/images/cover.jpg",
                    "OEBPS/cover.jpeg", "OEBPS/images/cover.jpeg"
                )
                for (name in knownNames) {
                    val entry = entries.firstOrNull {
                        it.name.equals(name, ignoreCase = true) || it.name.lowercase().endsWith("/$name")
                    }
                    if (entry != null) {
                        zip.getInputStream(entry).use { inp ->
                            val bmp = BitmapOptimizer.decodeSampledBitmapFromByteArray(inp.readBytes(), 360, 540)
                            if (bmp != null) return@extractEpubCover bmp.asImageBitmap()
                        }
                    }
                }

                // 3. Any image entry containing "cover"
                val byName = entries.firstOrNull { entry ->
                    !entry.isDirectory &&
                            entry.name.lowercase().contains("cover") &&
                            entry.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") }
                }
                if (byName != null) {
                    zip.getInputStream(byName).use { inp ->
                        val bmp = BitmapOptimizer.decodeSampledBitmapFromByteArray(inp.readBytes(), 360, 540)
                        if (bmp != null) return@extractEpubCover bmp.asImageBitmap()
                    }
                }

                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            tempFile.delete()
        }
    }
}
