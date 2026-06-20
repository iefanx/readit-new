package com.iefan.readout

import com.iefan.readout.utils.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {

    @Test
    fun testStripCitations() {
        val input = "This is a sentence [1]. And another one [12, 13] with brackets [1-5]."
        val expected = "This is a sentence. And another one with brackets."
        assertEquals(expected, TextCleaner.clean(input))
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

        assertEquals(expected, TextCleaner.clean(input))
    }

    @Test
    fun testStripUrls() {
        val input = "Check out https://google.com or www.wikipedia.org for details."
        val expected = "Check out or for details."
        assertEquals(expected, TextCleaner.clean(input))
    }

    @Test
    fun testCollapseMultipleSpaces() {
        val input = "This   is   a    very    spaced   sentence."
        val expected = "This is a very spaced sentence."
        assertEquals(expected, TextCleaner.clean(input))
    }
}
