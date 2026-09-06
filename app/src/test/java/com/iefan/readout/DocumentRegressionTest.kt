package com.iefan.readout

import com.iefan.readout.tts.DocumentParser
import com.iefan.readout.tts.SpeechChunks
import com.iefan.readout.tts.SpokenTextNormalizer
import com.iefan.readout.utils.DocumentText
import com.iefan.readout.utils.TextCleaner
import org.junit.Assert.*
import org.junit.Test

class DocumentRegressionTest {
    @Test fun longParagraphIsBoundedAndPreserved() {
        val text = "word ".repeat(100_000).trimEnd()
        val sentences = DocumentParser.parse(text)
        assertTrue(sentences.all { it.text.length <= DocumentParser.MAX_SENTENCE_CHARS })
        assertEquals(text.filterNot { it.isWhitespace() }, sentences.joinToString("") { it.text }.filterNot { it.isWhitespace() })
        sentences.forEach { assertEquals(text.substring(it.start, it.end), it.text) }
    }
    @Test fun multilingualBoundaries() {
        assertEquals(3, DocumentParser.parse("你好。世界！再见？").size)
        assertEquals(2, DocumentParser.parse("यह पहला वाक्य है। यह दूसरा वाक्य है।").size)
    }
    @Test fun normalizedTextRetainsTimesAndLanguage() {
        assertEquals("Meet at 5:30 p.m.", SpokenTextNormalizer.normalizeForSpeech("Meet at 5:30 p.m."))
        assertEquals("El descuento es 15%.", SpokenTextNormalizer.normalizeForSpeech("El descuento es 15%.", "es"))
    }
    @Test fun cleanupPreservesContentByDefault() {
        val text = "Year\n2026\n42\n[1]\nhttps://example.com"
        assertEquals(text, TextCleaner.clean(text))
    }
    @Test fun speechAndProviderChunksRespectUnicodeLimits() {
        val text = "😀नमस्ते world ".repeat(1000)
        val chunks = SpeechChunks.splitUtf8(text, 480)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray().size <= 480 })
        val speech = SpeechChunks.split(text, 3999)
        assertEquals(text, speech.joinToString(""))
        assertTrue(speech.all { it.length <= 3999 && !Character.isHighSurrogate(it.last()) })
    }
    @Test fun docxPreservesRunsTabsAndBreaks() {
        val xml = """<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:t>world</w:t><w:tab/><w:t>next</w:t><w:br/><w:t>line</w:t></w:r></w:p></w:body></w:document>"""
        assertEquals("Hello world\tnext\nline", DocumentText.docx(xml))
    }
    @Test fun htmlDoesNotDuplicateNestedBlocks() {
        assertEquals("Hello world", DocumentText.html("<article><blockquote><p>Hello world</p></blockquote></article>"))
    }
    @Test fun epubPathsResolveRelativeSegments() {
        assertEquals("Text/chapter 1.xhtml", DocumentText.resolveEntry("OEBPS/book.opf", "../Text/chapter%201.xhtml#start"))
    }
    @Test(expected = IllegalArgumentException::class) fun expandedInputIsBounded() {
        DocumentText.copyLimited("12345".byteInputStream(), java.io.ByteArrayOutputStream(), 4)
    }
    @Test fun utf16Import() {
        assertEquals("Hello नमस्ते", DocumentText.read("Hello नमस्ते".toByteArray(Charsets.UTF_16).inputStream()))
    }
}
