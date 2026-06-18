package com.iefan.readout.tts

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
    fun parse(text: String): List<SpeechSentence> {
        val sentences = mutableListOf<SpeechSentence>()
        if (text.isBlank()) return sentences

        // An intelligent regex-based sentence boundary detector that does not split on decimal numbers (e.g. 13.8)
        // Matches periods only if followed by whitespace or end of string, and question/exclamation marks or newlines
        val sentenceRegex = Regex("((?:[^.!?\\n]|\\.(?!\\s|\\$))+[.!?]*\\s*)")
        val wordRegex = Regex("[\\w\\d']+")

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
                    val matches = sentenceRegex.findAll(paragraphText)
                    var addedSentenceInParagraph = false

                    for (match in matches) {
                        val sentenceText = match.value
                        if (sentenceText.trim().isEmpty()) continue

                        val sentenceStart = start + match.range.first
                        val sentenceEnd = start + match.range.last + 1

                        // Now, parse individual words within this sentence
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

