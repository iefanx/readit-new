package com.iefan.readout.tts

import com.iefan.readout.utils.TextCleaner

data class SpeechWord(
    val text: String,
    val start: Int, // relative to the full text
    val end: Int    // relative to the full text
)

data class SpeechSentence(
    val index: Int,
    val text: String,
    val start: Int, // relative to the full text
    val end: Int,   // relative to the full text
    val words: List<SpeechWord>,
    val paragraphIndex: Int
)

object DocumentParser {
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "e.g", "i.e", "etc", "st",
        "approx", "inc", "corp", "vol", "p", "pp", "dept", "est", "fig", "no", "al", "gen", "rep", "sen", "gov",
        "u.s", "a.m", "p.m", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec"
    )

    private fun isAbbreviationEnd(text: String): Boolean {
        val trimmed = text.trimEnd()
        val withoutQuote = trimmed.trimEnd('"', '\'', '”', '’', '»', ')', ']')
        if (!withoutQuote.endsWith(".")) return false
        if (withoutQuote.endsWith("e.g.", ignoreCase = true) || 
            withoutQuote.endsWith("i.e.", ignoreCase = true) || 
            withoutQuote.endsWith("et al.", ignoreCase = true) ||
            withoutQuote.endsWith("u.s.", ignoreCase = true) ||
            withoutQuote.endsWith("a.m.", ignoreCase = true) ||
            withoutQuote.endsWith("p.m.", ignoreCase = true)) {
            return true
        }
        val lastWord = withoutQuote.substringBeforeLast('.')
            .substringAfterLast(' ')
            .trim('"', '\'', '“', '‘', '(', '[', '{', '«')
            .lowercase()
        // Single letter initial (e.g. J. K. Rowling, John F. Kennedy)
        if (lastWord.length == 1 && lastWord[0].isLetter()) {
            return true
        }
        return ABBREVIATIONS.contains(lastWord)
    }

    // Bounded iterative scanning: no recursive regex and no copying the remaining document.
    const val MAX_SENTENCE_CHARS = 800
    private val wordRegex = Regex("[\\p{L}\\p{M}\\p{N}']+")

    fun parse(rawText: String): List<SpeechSentence> {
        val text = TextCleaner.clean(rawText)
        val result = mutableListOf<SpeechSentence>()
        var start = 0
        var paragraph = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            var cursor = start
            var lastSpace = -1
            var paragraphBreak = false
            while (cursor < text.length) {
                val c = text[cursor]
                if (c.isWhitespace()) lastSpace = cursor
                if (c == '\n') {
                    var next = cursor + 1
                    while (next < text.length && (text[next] == ' ' || text[next] == '\t')) next++
                    if (next < text.length && text[next] == '\n') {
                        paragraphBreak = true
                        break
                    }
                }
                if (cursor - start >= MAX_SENTENCE_CHARS - 1) {
                    cursor = if (lastSpace > start) lastSpace else cursor
                    if (cursor > start && Character.isHighSurrogate(text[cursor - 1])) cursor--
                    break
                }
                if (c in ".!?。！？।॥") {
                    var end = cursor + 1
                    while (end < text.length && text[end] in ".!?。！？।॥\"”’')]") end++
                    val boundary = c in "。！？।॥" || end == text.length || text[end].isWhitespace()
                    if (boundary && !(c == '.' && isAbbreviationEnd(text.substring(start, end)))) {
                        cursor = end
                        break
                    }
                }
                cursor++
            }
            if (cursor <= start) cursor = (start + 1).coerceAtMost(text.length)
            val sentenceText = text.substring(start, cursor)
            val words = wordRegex.findAll(sentenceText).map {
                SpeechWord(it.value, start + it.range.first, start + it.range.last + 1)
            }.toList()
            result.add(SpeechSentence(result.size, sentenceText, start, cursor, words, paragraph))
            start = cursor
            if (paragraphBreak) paragraph++
        }
        return result
    }
}
