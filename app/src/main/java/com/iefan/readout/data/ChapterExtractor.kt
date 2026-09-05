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
        // Pattern 1: Chapter 1: The Beginning, Chapter IV - Arriving, Chapter One
        val chapterPattern = Pattern.compile(
            """^(?i)chapter\s+(\d+|[ivxldm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)(?:\s*[:.-]\s*(.*))?$"""
        )
        // Pattern 2: 1. Introduction, 2.3 Methodology (Short heading)
        val numberedPattern = Pattern.compile(
            """^(\d+(?:\.\d+)*)\.?\s+([A-Z][A-Za-z0-9'\s,-]{2,60})$"""
        )
        // Pattern 3: Roman numeral prefix (e.g., I. Introduction, IV. The End)
        val romanPattern = Pattern.compile(
            """^([ivxldm]+)\.?\s+([A-Z][A-Za-z0-9'\s,-]{2,60})$""", Pattern.CASE_INSENSITIVE
        )
        // Pattern 4: Prologue, Epilogue, Introduction, Conclusion, etc.
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
                    val romanMatcher = romanPattern.matcher(trimmedLine)
                    if (romanMatcher.matches()) {
                        val roman = romanMatcher.group(1)
                        val name = romanMatcher.group(2)
                        titleText = "$roman $name"
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
        // Support 2 or more dots/spaces/dashes
        val indexLinePattern = Pattern.compile(
            """^(.*?)(?:\.{2,}|-{2,}|_{2,}|\s{2,})(?:\bpage\b\s*)?(\d+)\s*$"""
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
                val entries = zip.entries().toList()
                val tocItems = mutableListOf<Pair<String, String>>() // (title, href)

                // 1. Try NCX outline (EPUB 2 standard)
                val ncxEntry = entries.firstOrNull { it.name.lowercase().endsWith(".ncx") }
                if (ncxEntry != null) {
                    try {
                        val xml = zip.getInputStream(ncxEntry).bufferedReader(Charsets.UTF_8).readText()
                        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                        val navPoints = doc.select("navPoint")
                        for (np in navPoints) {
                            val title = np.selectFirst("navLabel > text")?.text()?.trim() ?: ""
                            val src = np.selectFirst("content")?.attr("src")?.trim() ?: ""
                            if (title.isNotEmpty()) {
                                tocItems.add(Pair(title, src))
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 2. Try EPUB 3 Navigation Document (nav.xhtml, toc.xhtml etc.) if NCX was absent or empty
                if (tocItems.isEmpty()) {
                    val navEntry = entries.firstOrNull { entry ->
                        val name = entry.name.lowercase()
                        !entry.isDirectory && (
                            name.endsWith("nav.xhtml") || name.endsWith("nav.html") || 
                            name.endsWith("toc.xhtml") || name.endsWith("toc.html") || 
                            name.endsWith("navigation.xhtml") || name.endsWith("navigation.html")
                        )
                    }
                    if (navEntry != null) {
                        try {
                            val html = zip.getInputStream(navEntry).bufferedReader(Charsets.UTF_8).readText()
                            val doc = Jsoup.parse(html)
                            val links = doc.select("nav[epub:type=toc] a, nav#toc a, ol.toc a, a")
                            for (link in links) {
                                val title = link.text().trim()
                                val src = link.attr("href").trim()
                                if (title.isNotEmpty()) {
                                    tocItems.add(Pair(title, src))
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                // Match TOC chapter titles against the extracted text
                if (tocItems.isNotEmpty() && extractedText.isNotBlank()) {
                    var lastOffset = 0
                    for ((title, _) in tocItems) {
                        // Look for the title in the extracted text at or after the previous chapter offset
                        val searchTitle = title.trim()
                        if (searchTitle.length < 2) continue

                        // Try exact match or line match
                        val lineRegex = Pattern.compile(
                            """(?m)^\s*""" + Pattern.quote(searchTitle) + """\s*$""",
                            Pattern.CASE_INSENSITIVE
                        )
                        val matcher = lineRegex.matcher(extractedText)
                        var foundOffset = -1
                        while (matcher.find()) {
                            if (matcher.start() >= lastOffset) {
                                foundOffset = matcher.start()
                                break
                            }
                        }

                        // Fallback: substring search at or after lastOffset
                        if (foundOffset == -1) {
                            val idx = extractedText.indexOf(searchTitle, lastOffset)
                            if (idx != -1) {
                                foundOffset = idx
                            }
                        }

                        if (foundOffset != -1) {
                            candidates.add(ChapterCandidate(searchTitle, foundOffset))
                            lastOffset = foundOffset
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val sortedCandidates = candidates.distinctBy { it.charOffset }.sortedBy { it.charOffset }
        if (sortedCandidates.size >= 2) {
            return sortedCandidates
        }

        // Fallback to text sniffer if TOC produced no matches
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
                    val title = (bookmark["Title"] as? String)?.trim() ?: ""
                    val pageInfo = bookmark["Page"] as? String
                    if (title.isNotEmpty() && pageInfo != null) {
                        val pageNum = pageInfo.split(" ").firstOrNull()?.toIntOrNull()
                        if (pageNum != null && pageNum > 0 && pageNum < pageStartOffsets.size) {
                            val offset = pageStartOffsets[pageNum].coerceIn(0, extractedText.length)
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
        if (sortedCandidates.size >= 2) {
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
