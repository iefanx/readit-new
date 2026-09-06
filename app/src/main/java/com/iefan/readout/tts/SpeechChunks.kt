package com.iefan.readout.tts

/** Bounded chunks never split UTF-16 surrogate pairs; provider limits count UTF-8 bytes. */
object SpeechChunks {
    fun split(text: String, maxChars: Int): List<String> = splitBy(text, maxChars) { it.length }
    fun splitUtf8(text: String, maxBytes: Int): List<String> = splitBy(text, maxBytes) { it.toByteArray(Charsets.UTF_8).size }

    private fun splitBy(text: String, limit: Int, size: (String) -> Int): List<String> {
        require(limit >= 4)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var used = 0
            var space = -1
            while (end < text.length) {
                val next = end + Character.charCount(text.codePointAt(end))
                val amount = size(text.substring(end, next))
                if (used + amount > limit) break
                used += amount
                if (text[end].isWhitespace()) space = next
                end = next
            }
            if (end < text.length && space > start) end = space
            result.add(text.substring(start, end))
            start = end
        }
        return result
    }
}
