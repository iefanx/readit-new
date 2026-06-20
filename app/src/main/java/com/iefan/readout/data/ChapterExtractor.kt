package com.iefan.readout.data

import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.SimpleBookmark
import org.jsoup.Jsoup
import java.io.File
import java.util.regex.Pattern
import com.iefan.readout.utils.TextCleaner

data class ChapterCandidate(
    val title: String,
    val charOffset: Int
)

object ChapterExtractor {

    // 1. Universal Text/Heading-based Sniffer
    fun extractChaptersFromText(rawContent: String): List<ChapterCandidate> {
        val content = TextCleaner.clean(rawContent)
        val candidates = mutableListOf<ChapterCandidate>()
        val lines = content.split("\n")
        var currentOffset = 0
        
        // Patterns to match headings:
        // Pattern 1: Chapter 1: The Beginning, Chapter IV - Arriving
        val chapterPattern = Pattern.compile(
            """^(?i)chapter\s+(\d+|[ivxldm]+)(?:\s*[:.-]\s*(.*))?$"""
        )
        // Pattern 2: 1. Introduction, 2.3 Methodology (Short heading)
        val numberedPattern = Pattern.compile(
            """^(\d+(?:\.\d+)*)\.?\s+([A-Z][A-Za-z0-9'\s,-]{2,60})$"""
        )
        // Pattern 3: Prologue, Epilogue, Introduction, Conclusion, etc.
        val standardPattern = Pattern.compile(
            """^(?i)(prologue|epilogue|introduction|conclusion|preface|foreword|afterword|appendix(\s+\w+)?)$"""
        )

        for (line in lines) {
            val trimmedLine = line.trim()
            val len = line.length + 1 // +1 for the newline
            
            // Check if it's a short line (usually headings are short, < 100 chars)
            if (trimmedLine.length in 3..100) {
                var matched = false
                var titleText = ""

                val chapMatcher = chapterPattern.matcher(trimmedLine)
                if (chapMatcher.matches()) {
                    val num = chapMatcher.group(1)
                    val rest = chapMatcher.group(2)
                    titleText = if (!rest.isNullOrBlank()) "Chapter $num: $rest" else "Chapter $num"
                    matched = true
                }

                if (!matched) {
                    val numMatcher = numberedPattern.matcher(trimmedLine)
                    if (numMatcher.matches()) {
                        val num = numMatcher.group(1)
                        val name = numMatcher.group(2)
                        titleText = "$num $name"
                        matched = true
                    }
                }

                if (!matched) {
                    val stdMatcher = standardPattern.matcher(trimmedLine)
                    if (stdMatcher.matches()) {
                        titleText = trimmedLine.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        matched = true
                    }
                }

                if (matched) {
                    candidates.add(ChapterCandidate(titleText, currentOffset))
                }
            }
            currentOffset += len
        }

        // If we found a reasonable list of chapters (not just 1 or 2, and not too many), return it
        if (candidates.size in 3..150) {
            return candidates
        }

        // If we failed, let's try to search for a Table of Contents index page
        return reconstructFromIndexPage(content)
    }

    // 2. Index / Table of Contents Page Reconstructer
    private fun reconstructFromIndexPage(content: String): List<ChapterCandidate> {
        val candidates = mutableListOf<ChapterCandidate>()
        
        // Scan the first 30,000 characters for Table of Contents markers
        val searchHeaderLimit = minOf(content.length, 30000)
        val headerArea = content.substring(0, searchHeaderLimit)
        
        // Look for "Table of Contents", "Contents", "Index", etc.
        val tocPattern = Pattern.compile(
            """(?i)\b(table of contents|contents|index)\b"""
        )
        val tocMatcher = tocPattern.matcher(headerArea)
        if (!tocMatcher.find()) {
            return emptyList() // No index page found
        }
        
        // Start scanning lines after the TOC header
        val startFromIndex = tocMatcher.end()
        val remainingHeaderArea = headerArea.substring(startFromIndex)
        val lines = remainingHeaderArea.split("\n")
        
        // Pattern to match: [Chapter/Section Title] ...... [Page Number]
        // or [Chapter/Section Title] [Page Number]
        // Or Chapter 1: The Beginning [Page Number]
        val indexLinePattern = Pattern.compile(
            """^(.*?)(?:\.{3,}|-{3,}|_{3,}|\s{3,})(?:\bpage\b\s*)?(\d+)\s*$"""
        )
        
        val parsedPageTargets = mutableListOf<Pair<String, Int>>()
        var lineCount = 0
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            lineCount++
            if (lineCount > 50) break // Stop scanning after 50 lines to avoid reading whole book
            
            val matcher = indexLinePattern.matcher(trimmed)
            if (matcher.matches()) {
                val title = matcher.group(1)?.trim() ?: ""
                val pageNum = matcher.group(2)?.toIntOrNull()
                
                if (title.isNotEmpty() && pageNum != null && pageNum > 0) {
                    parsedPageTargets.add(Pair(title, pageNum))
                }
            }
        }
        
        if (parsedPageTargets.isEmpty()) {
            return emptyList()
        }

        // Now, we need to map page numbers or find where the chapters start in the main text.
        // For a generic text file (where we don't have page boundaries), we search for the exact titles or chapter names in the main text.
        for (target in parsedPageTargets) {
            val title = target.first
            // Search for where this title starts in the text (prioritizing matches that are on a line by themselves)
            val titleRegex = Pattern.compile(
                """(?m)^\s*""" + Pattern.quote(title) + """\s*$"""
            )
            val textMatcher = titleRegex.matcher(content)
            if (textMatcher.find()) {
                candidates.add(ChapterCandidate(title, textMatcher.start()))
            } else {
                // Try a simpler match inside the text
                val simpleIndex = content.indexOf(title, startFromIndex + 500) // Skip the index page itself
                if (simpleIndex != -1) {
                    candidates.add(ChapterCandidate(title, simpleIndex))
                }
            }
        }

        return candidates
    }

    // 3. EPUB NCX / nav.xhtml outline parser
    fun extractChaptersFromEpub(epubFile: File, extractedText: String): List<ChapterCandidate> {
        val candidates = mutableListOf<ChapterCandidate>()
        try {
            java.util.zip.ZipFile(epubFile).use { zip ->
                // Look for NCX file
                val ncxEntry = zip.entries().asSequence().find { it.name.endsWith(".ncx", ignoreCase = true) }
                if (ncxEntry != null) {
                    val xml = zip.getInputStream(ncxEntry).use { it.bufferedReader(Charsets.UTF_8).readText() }
                    val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                    val navPoints = doc.select("navPoint")
                    
                    val tocMap = mutableListOf<Pair<String, String>>() // Pair of (title, srcFileSuffix)
                    for (np in navPoints) {
                        val title = np.selectFirst("navLabel > text")?.text() ?: ""
                        val src = np.selectFirst("content")?.attr("src") ?: ""
                        if (title.isNotEmpty() && src.isNotEmpty()) {
                            // Strip fragment
                            val cleanSrc = src.substringBefore("#").substringAfterLast("/")
                            if (cleanSrc.isNotEmpty()) {
                                tocMap.add(Pair(title, cleanSrc))
                            }
                        }
                    }
                    
                    // We also need to know the offset of each file in the extracted text!
                    val entries = zip.entries().toList()
                    val textEntries = entries.filter { entry ->
                        val name = entry.name.lowercase()
                        !entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))
                    }.sortedBy { it.name }
                    
                    var currentOffset = 0
                    val fileStartOffsets = mutableMapOf<String, Int>()
                    
                    for (entry in textEntries) {
                        val fileName = entry.name.substringAfterLast("/")
                        fileStartOffsets[fileName] = currentOffset
                        
                        zip.getInputStream(entry).use { stream ->
                            val htmlContent = stream.bufferedReader(Charsets.UTF_8).readText()
                            val docHtml = Jsoup.parse(htmlContent)
                            docHtml.select("script, style, head, header, footer, nav, iframe, noscript").remove()
                            val blocks = docHtml.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, pre")
                            val contentBuilder = StringBuilder()
                            for (b in blocks) {
                                contentBuilder.append(b.text().trim()).append("\n\n")
                            }
                            currentOffset += contentBuilder.toString().length + 2 // +2 for the double newlines separating files
                        }
                    }
                    
                    // Match TOC entries to file offsets
                    for (item in tocMap) {
                        val title = item.first
                        val srcFile = item.second
                        val offset = fileStartOffsets[srcFile]
                        if (offset != null) {
                            candidates.add(ChapterCandidate(title, offset))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Deduplicate and return
        val sortedCandidates = candidates.distinctBy { it.charOffset }.sortedBy { it.charOffset }
        if (sortedCandidates.isNotEmpty()) {
            return sortedCandidates
        }

        // Fallback to text-based extraction
        return extractChaptersFromText(extractedText)
    }

    // 4. PDF Bookmark Outline / Index Page Map Parser
    fun extractChaptersFromPdf(pdfFile: File, extractedText: String, pageStartOffsets: List<Int>): List<ChapterCandidate> {
        val candidates = mutableListOf<ChapterCandidate>()
        var reader: PdfReader? = null
        try {
            reader = PdfReader(pdfFile.inputStream())
            val bookmarks = SimpleBookmark.getBookmark(reader)
            if (bookmarks != null) {
                val flatBookmarks = mutableListOf<Map<String, Any>>()
                fun flatten(list: List<Map<String, Any>>) {
                    for (item in list) {
                        flatBookmarks.add(item)
                        @Suppress("UNCHECKED_CAST")
                        val kids = item["Kids"] as? List<Map<String, Any>>
                        if (kids != null) {
                            flatten(kids)
                        }
                    }
                }
                @Suppress("UNCHECKED_CAST")
                flatten(bookmarks as List<Map<String, Any>>)

                for (bookmark in flatBookmarks) {
                    val title = bookmark["Title"] as? String
                    val pageInfo = bookmark["Page"] as? String
                    if (title != null && pageInfo != null) {
                        val pageNum = pageInfo.split(" ").firstOrNull()?.toIntOrNull()
                        if (pageNum != null && pageNum > 0 && pageNum < pageStartOffsets.size) {
                            val offset = pageStartOffsets[pageNum]
                            candidates.add(ChapterCandidate(title, offset))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            reader?.close()
        }

        val sortedCandidates = candidates.distinctBy { it.charOffset }.sortedBy { it.charOffset }
        if (sortedCandidates.isNotEmpty()) {
            return sortedCandidates
        }

        // Fallback: Check index page and match page start offsets
        val indexPageCandidates = reconstructFromIndexPage(extractedText)
        if (indexPageCandidates.isNotEmpty()) {
            // If we have page numbers, map them to page start offsets
            val mappedCandidates = mutableListOf<ChapterCandidate>()
            for (cand in indexPageCandidates) {
                // Check if title has a page number matched or try to find index page candidates mapping
                mappedCandidates.add(cand)
            }
            return mappedCandidates
        }

        // Final fallback: standard sniffer
        return extractChaptersFromText(extractedText)
    }
}
