package com.iefan.readout

import com.iefan.readout.utils.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {

    @Test
    fun testStripCitations() {
        val input = "This is a sentence [1]. And another one [12, 13] with brackets [1-5]."
        val expected = "This is a sentence. And another one with brackets."
        assertEquals(expected, TextCleaner.clean(input, removeAnnotations = true))
    }

    @Test
    fun testStripPageNumbers() {
        val input = """
            Chapter 1
            Page 12
            This is the body of page 12.
            - 13 -
            More body text.
            14 / 350
            Even more text.
            15
            Final text.
            1
            Keep single digit heading.
        """.trimIndent()

        val expected = """
            Chapter 1
            
            This is the body of page 12.
            
            More body text.
            
            Even more text.
            
            Final text.
            1
            Keep single digit heading.
        """.trimIndent()

        assertEquals(expected, TextCleaner.clean(input, removeAnnotations = true))
    }

    @Test
    fun testStripUrls() {
        val input = "Check out https://google.com or www.wikipedia.org for details."
        val expected = "Check out or for details."
        assertEquals(expected, TextCleaner.clean(input, removeAnnotations = true))
    }

    @Test
    fun testCollapseMultipleSpaces() {
        val input = "This   is   a    very    spaced   sentence."
        val expected = "This is a very spaced sentence."
        assertEquals(expected, TextCleaner.clean(input, removeAnnotations = true))
    }

    @Test
    fun testDocumentParserAbbreviations() {
        val input = "Dr. Watson met Mr. Holmes at the park. They discussed e.g. the case vs. Moriarty et al. yesterday."
        val sentences = com.iefan.readout.tts.DocumentParser.parse(input)
        assertEquals(2, sentences.size)
        assertEquals("Dr. Watson met Mr. Holmes at the park.", sentences[0].text.trim())
        assertEquals("They discussed e.g. the case vs. Moriarty et al. yesterday.", sentences[1].text.trim())
    }

    @Test
    fun testSpokenTextNormalizer() {
        val input = "The price was $24.50 (or $10) with a 15% discount for Chapter IV. She had 1/2 share — truly amazing..."
        val normalized = com.iefan.readout.tts.SpokenTextNormalizer.normalizeForSpeech(input)
        assertEquals("The price was 24 dollars and 50 cents (or 10 dollars) with a 15 percent discount for Chapter 4. She had one half share, truly amazing.", normalized)
    }

    @Test
    fun testSpokenTextNormalizerQuotesAndAbbreviations() {
        val input = "\"Dr. Watson,\" said Mr. Holmes, \"the case vs. Moriarty is 99% solved!\""
        val normalized = com.iefan.readout.tts.SpokenTextNormalizer.normalizeForSpeech(input)
        assertEquals("Doctor Watson, said Mister Holmes, the case versus Moriarty is 99 percent solved!", normalized)
    }

    @Test
    fun testDocumentParserUnicodeAcrossLanguages() {
        // Spanish with accents
        val spanish = "El niño está feliz y emocionado."
        val spanishSentences = com.iefan.readout.tts.DocumentParser.parse(spanish)
        assertEquals(1, spanishSentences.size)
        val spanishWords = spanishSentences[0].words.map { it.text }
        org.junit.Assert.assertTrue("Expected 'niño' in words: $spanishWords", spanishWords.contains("niño"))
        org.junit.Assert.assertTrue("Expected 'está' in words: $spanishWords", spanishWords.contains("está"))

        // French with accents
        val french = "L'élève lit un livre passionnant."
        val frenchSentences = com.iefan.readout.tts.DocumentParser.parse(french)
        assertEquals(1, frenchSentences.size)
        val frenchWords = frenchSentences[0].words.map { it.text }
        org.junit.Assert.assertTrue("Expected 'élève' in words: $frenchWords", frenchWords.any { it.contains("élève") })

        // German with umlauts and eszett
        val german = "Die große Straße führt zur Universität."
        val germanSentences = com.iefan.readout.tts.DocumentParser.parse(german)
        assertEquals(1, germanSentences.size)
        val germanWords = germanSentences[0].words.map { it.text }
        org.junit.Assert.assertTrue("Expected 'große' in words: $germanWords", germanWords.contains("große"))

        // Russian Cyrillic
        val russian = "Привет мир, добро пожаловать в будущее."
        val russianSentences = com.iefan.readout.tts.DocumentParser.parse(russian)
        assertEquals(1, russianSentences.size)
        val russianWords = russianSentences[0].words.map { it.text }
        org.junit.Assert.assertTrue("Expected Russian words: $russianWords", russianWords.contains("Привет"))

        // Hindi Devanagari
        val hindi = "नमस्ते दुनिया, आप कैसे हैं?"
        val hindiSentences = com.iefan.readout.tts.DocumentParser.parse(hindi)
        assertEquals(1, hindiSentences.size)
        val hindiWords = hindiSentences[0].words.map { it.text }
        org.junit.Assert.assertTrue("Expected Hindi words: $hindiWords", hindiWords.contains("नमस्ते"))
    }

    @Test
    fun testDocumentParserInitialsAndDialogueQuotes() {
        val input = "J. K. Rowling wrote \"Harry Potter\" in the U.S. at 5 p.m. yesterday. \"It was brilliant!\" said Alice."
        val sentences = com.iefan.readout.tts.DocumentParser.parse(input)
        assertEquals(3, sentences.size)
        assertEquals("J. K. Rowling wrote \"Harry Potter\" in the U.S. at 5 p.m. yesterday.", sentences[0].text.trim())
        assertEquals("\"It was brilliant!\"", sentences[1].text.trim())
        assertEquals("said Alice.", sentences[2].text.trim())
    }

    @Test
    fun testChapterExtractorHeadings() {
        val documentText = """
            Prologue
            The journey begins here in the dark woods.
            
            Chapter 1: The Gathering
            Everyone assembled at dawn.
            
            2. Preparations
            Supplies were gathered and counted carefully.
            
            III. The Departure
            The ships sailed out into the open sea.
            
            Epilogue
            Peace was restored to the realm.
        """.trimIndent()

        val chapters = com.iefan.readout.data.ChapterExtractor.extractChaptersFromText(documentText)
        org.junit.Assert.assertTrue("Expected at least 3 extracted chapters, got ${chapters.size}", chapters.size >= 3)
        val titles = chapters.map { it.title }
        org.junit.Assert.assertTrue("Expected Prologue in titles", titles.any { it.contains("Prologue", ignoreCase = true) })
        org.junit.Assert.assertTrue("Expected Chapter 1 in titles", titles.any { it.contains("Chapter 1", ignoreCase = true) })
    }
}

