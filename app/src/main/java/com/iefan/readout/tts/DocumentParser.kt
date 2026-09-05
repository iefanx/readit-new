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

    fun parse(rawText: String): List<SpeechSentence> {
        val text = TextCleaner.clean(rawText)
        val sentences = mutableListOf<SpeechSentence>()
        if (text.isBlank()) return sentences

        // An intelligent regex-based sentence boundary detector that does not split on decimal numbers (e.g. 13.8)
        // Matches punctuation and closing quotes/brackets attached to sentence ends
        val sentenceRegex = Regex("((?:[^.!?\\n]|\\.(?!\\s|\\$))+[.!?]*[\"”’')\\]]*\\s*)")
        val wordRegex = Regex("[\\p{L}\\p{M}\\p{N}']+")

        var sentenceIndex = 0
        var paragraphIndex = 0
        var start = 0
        val len = text.length

        while (start < len) {
            var end = text.indexOf('\n', start)
            if (end == -1) {
                end = len
            }

            if (start < end) {
                // Quick content check to avoid regex running on purely whitespace lines
                var hasContent = false
                for (i in start until end) {
                    if (!text[i].isWhitespace()) {
                        hasContent = true
                        break
                    }
                }

                if (hasContent) {
                    val paragraphText = text.substring(start, end)
                    val rawMatches = sentenceRegex.findAll(paragraphText).toList()
                    var addedSentenceInParagraph = false

                    // Merge abbreviations so they don't break sentences awkwardly
                    val mergedMatches = mutableListOf<Pair<IntRange, String>>()
                    var matchIdx = 0
                    while (matchIdx < rawMatches.size) {
                        val m = rawMatches[matchIdx]
                        var currentRange = m.range
                        var currentText = m.value

                        while (matchIdx + 1 < rawMatches.size && isAbbreviationEnd(currentText)) {
                            val nextM = rawMatches[matchIdx + 1]
                            currentRange = currentRange.first..nextM.range.last
                            currentText += nextM.value
                            matchIdx++
                        }
                        mergedMatches.add(currentRange to currentText)
                        matchIdx++
                    }

                    for (match in mergedMatches) {
                        val sentenceText = match.second
                        if (sentenceText.trim().isEmpty()) continue

                        val sentenceStart = start + match.first.first
                        val sentenceEnd = start + match.first.last + 1

                        // Parse individual words within this sentence
                        val words = mutableListOf<SpeechWord>()
                        val wordMatches = wordRegex.findAll(sentenceText)

                        for (wordMatch in wordMatches) {
                            val wordText = wordMatch.value
                            val wordStartInSentence = wordMatch.range.first
                            val wordEndInSentence = wordMatch.range.last + 1

                            words.add(
                                SpeechWord(
                                    text = wordText,
                                    start = sentenceStart + wordStartInSentence,
                                    end = sentenceStart + wordEndInSentence
                                )
                            )
                        }

                        sentences.add(
                            SpeechSentence(
                                index = sentenceIndex++,
                                text = sentenceText,
                                start = sentenceStart,
                                end = sentenceEnd,
                                words = words,
                                paragraphIndex = paragraphIndex
                            )
                        )
                        addedSentenceInParagraph = true
                    }
                    if (addedSentenceInParagraph) {
                        paragraphIndex++
                    }
                }
            }

            start = end + 1
        }
        return sentences
    }
}

