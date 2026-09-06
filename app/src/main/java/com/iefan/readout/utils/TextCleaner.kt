package com.iefan.readout.utils

object TextCleaner {
    private val CITATION_BRACKETS_REGEX = Regex("\\[\\d+(?:[\\s,.-]*\\d+)*\\]")
    
    // Page number regexes for single lines
    private val PAGE_NUMBER_REGEX_1 = Regex("(?i)^\\s*page\\s+\\d+\\s*$")
    private val PAGE_NUMBER_REGEX_2 = Regex("(?i)^\\s*page\\s+\\d+\\s+of\\s+\\d+\\s*$")
    private val PAGE_NUMBER_REGEX_3 = Regex("^\\s*-\\s*\\d+\\s*-\\s*$")
    private val PAGE_NUMBER_REGEX_4 = Regex("^\\s*\\d{2,}\\s*$") // 2 or more digits standalone
    private val PAGE_NUMBER_REGEX_5 = Regex("^\\s*\\d+\\s*/\\s*\\d+\\s*$")
    
    // URL regex
    private val URL_REGEX = Regex("https?://[^\\s/$.?#].[^\\s]*|www\\.[^\\s/$.?#].[^\\s]*")

    // Pre-compiled to avoid allocating a new Regex object on every call to clean()
    private val MULTIPLE_SPACES_REGEX = Regex(" {2,}")
    private val SPACE_BEFORE_PUNCT_REGEX = Regex(" +([.,!?;:])")

    @JvmOverloads
    fun clean(text: String, removeAnnotations: Boolean = false): String {
        if (text.isBlank()) return text

        // 1. Process line by line to remove page numbers/headers
        val lines = text.split("\n")
        val cleanedLines = lines.map { line ->
            val trimmed = line.trim()
            if (removeAnnotations && isPageNumberOrHeader(trimmed)) {
                "" // strip page numbers
            } else {
                // Remove URLs within the line
                if (removeAnnotations) line.replace(URL_REGEX, "") else line
            }
        }

        val joined = cleanedLines.joinToString("\n")

        // 2. Remove bracketed citations [1], [1,2], [1-3] etc.
        val withoutCitations = if (removeAnnotations) joined.replace(CITATION_BRACKETS_REGEX, "") else joined

        // 3. Clean up multiple consecutive spaces (collapsing them) while keeping newlines intact
        val collapsed = withoutCitations.split("\n")
            .map { line -> line.replace(MULTIPLE_SPACES_REGEX, " ").trimEnd() }
            .joinToString("\n")

        // 4. Remove spaces before punctuation (like periods, commas, colons, semicolons)
        return collapsed.replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
    }

    private fun isPageNumberOrHeader(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        return PAGE_NUMBER_REGEX_1.matches(trimmed) ||
                PAGE_NUMBER_REGEX_2.matches(trimmed) ||
                PAGE_NUMBER_REGEX_3.matches(trimmed) ||
                PAGE_NUMBER_REGEX_4.matches(trimmed) ||
                PAGE_NUMBER_REGEX_5.matches(trimmed)
    }
}
