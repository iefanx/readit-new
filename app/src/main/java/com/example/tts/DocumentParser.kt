package com.example.tts

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
    val words: List<SpeechWord>
)

object DocumentParser {
    fun parse(text: String): List<SpeechSentence> {
        val sentences = mutableListOf<SpeechSentence>()
        if (text.isBlank()) return sentences

        // A simple but robust regex-based sentence boundary detector
        // Matches periods, question marks, exclamation marks followed by spaces or newlines, or lines
        val regex = Regex("([^.!?\\n]+[.!?]*\\s*)")
        val matches = regex.findAll(text)

        var sentenceIndex = 0
        for (match in matches) {
            val sentenceText = match.value
            if (sentenceText.trim().isEmpty()) continue

            val sentenceStart = match.range.first
            val sentenceEnd = match.range.last + 1

            // Now, parse individual words within this sentence
            val words = mutableListOf<SpeechWord>()
            val wordRegex = Regex("[\\w\\d']+")
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
                    words = words
                )
            )
        }
        return sentences
    }
}
