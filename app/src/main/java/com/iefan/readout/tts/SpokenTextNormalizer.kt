package com.iefan.readout.tts

/**
 * Normalizes text prior to passing to TextToSpeech engine so it sounds warm, natural,
 * and human. Expands currencies, percentages, fractions, Roman numerals in chapters,
 * and injects breath/clause cadence at em-dashes and ellipses without affecting the UI text.
 */
object SpokenTextNormalizer {
    // Currencies with cents & whole numbers
    private val USD_CENTS_REGEX = Regex("""\$(\d+)\.(\d{2})\b""")
    private val USD_REGEX = Regex("""\$(\d+)\b""")
    private val EUR_CENTS_REGEX = Regex("""€(\d+)\.(\d{2})\b""")
    private val EUR_REGEX = Regex("""€(\d+)\b""")
    private val GBP_CENTS_REGEX = Regex("""£(\d+)\.(\d{2})\b""")
    private val GBP_REGEX = Regex("""£(\d+)\b""")
    private val INR_REGEX = Regex("""₹(\d+)\b""")

    // Percentages: 99%, 4.5%
    private val PERCENT_REGEX = Regex("""(\d+(?:\.\d+)?)%""")

    // Common fractions: 1/2, 1/4, 3/4, 1/3, 2/3
    private val FRACTION_MAP = mapOf(
        Regex("""\b1/2\b""") to "one half",
        Regex("""\b1/4\b""") to "one quarter",
        Regex("""\b3/4\b""") to "three quarters",
        Regex("""\b1/3\b""") to "one third",
        Regex("""\b2/3\b""") to "two thirds"
    )

    // Roman Numerals in Book/Chapter/Part context: "Chapter IV" -> "Chapter 4", "Part III" -> "Part 3"
    private val CHAPTER_ROMAN_REGEX = Regex("""(?i)\b(chapter|part|section|book|act|scene|volume|vol\.?)\s+(X{0,3}(?:IX|IV|V?I{0,3}))\b""")
    private val ROMAN_NUMERALS = mapOf(
        "I" to "1", "II" to "2", "III" to "3", "IV" to "4", "V" to "5",
        "VI" to "6", "VII" to "7", "VIII" to "8", "IX" to "9", "X" to "10",
        "XI" to "11", "XII" to "12", "XIII" to "13", "XIV" to "14", "XV" to "15",
        "XVI" to "16", "XVII" to "17", "XVIII" to "18", "XIX" to "19", "XX" to "20"
    )

    // Em-dashes and hyphens for breathing cadence: "—", "--" -> ", "
    private val EM_DASH_REGEX = Regex("""\s*—\s*|\s*--\s*""")
    
    // Ellipses for natural pauses: "...", "…" -> ", "
    private val ELLIPSIS_REGEX = Regex("""\s*(?:\.{3,}|…)\s*""")

    // Speed units
    private val SPEED_KMH_REGEX = Regex("""(?i)\b(\d+)\s*km/h\b""")
    private val SPEED_MPH_REGEX = Regex("""(?i)\b(\d+)\s*mph\b""")

    // Symbols: & -> and, + -> plus
    private val AMPERSAND_REGEX = Regex("""(?<=\w)\s*&\s*(?=\w)""")
    private val PLUS_SIGN_REGEX = Regex("""(?<=\w)\s*\+\s*(?=\w)""")

    fun normalizeForSpeech(rawText: String): String {
        if (rawText.isBlank()) return rawText
        var text = rawText

        // 1. Em-dashes, Ellipses, Semicolons & Colons: Shape into natural human breathing and clause intervals
        text = text.replace(EM_DASH_REGEX, ", ")
        text = text.replace(ELLIPSIS_REGEX, ", ")
        text = text.replace(Regex("""(?<=\w)\s*;\s*(?=\w)"""), ", ")
        text = text.replace(Regex("""(?<=\w)\s*:\s*(?=\w)"""), ", ")
        // Dialogue quotes: strip quotes right after punctuation so TTS pauses naturally on terminal marks
        text = text.replace(Regex("""(?<=[.!?])\s*["”'»]+\s*"""), " ")

        // 2. Currencies with cents
        text = text.replace(USD_CENTS_REGEX) { match ->
            val dollars = match.groupValues[1]
            val cents = match.groupValues[2]
            "$dollars dollars and $cents cents"
        }
        text = text.replace(USD_REGEX) { match ->
            "${match.groupValues[1]} dollars"
        }
        text = text.replace(EUR_CENTS_REGEX) { match ->
            val euros = match.groupValues[1]
            val cents = match.groupValues[2]
            "$euros euros and $cents cents"
        }
        text = text.replace(EUR_REGEX) { match ->
            "${match.groupValues[1]} euros"
        }
        text = text.replace(GBP_CENTS_REGEX) { match ->
            val pounds = match.groupValues[1]
            val pence = match.groupValues[2]
            "$pounds pounds and $pence pence"
        }
        text = text.replace(GBP_REGEX) { match ->
            "${match.groupValues[1]} pounds"
        }
        text = text.replace(INR_REGEX) { match ->
            "${match.groupValues[1]} rupees"
        }

        // 3. Percentages
        text = text.replace(PERCENT_REGEX) { match ->
            "${match.groupValues[1]} percent"
        }

        // 4. Fractions
        for ((pattern, replacement) in FRACTION_MAP) {
            text = text.replace(pattern, replacement)
        }

        // 5. Units
        text = text.replace(SPEED_KMH_REGEX) { match ->
            "${match.groupValues[1]} kilometers per hour"
        }
        text = text.replace(SPEED_MPH_REGEX) { match ->
            "${match.groupValues[1]} miles per hour"
        }

        // 6. Roman numerals in chapters / parts
        text = text.replace(CHAPTER_ROMAN_REGEX) { match ->
            val prefix = match.groupValues[1]
            val roman = match.groupValues[2].uppercase()
            val arabic = ROMAN_NUMERALS[roman] ?: roman
            "$prefix $arabic"
        }

        // 7. Symbols
        text = text.replace(AMPERSAND_REGEX, " and ")
        text = text.replace(PLUS_SIGN_REGEX, " plus ")

        // 8. Clean up redundant spaces and comma clusters
        text = text.replace(Regex("\\s*,\\s*,"), ",")
        text = text.replace(Regex("\\s+,"), ",")
        text = text.replace(Regex(",\\s*\\."), ".")
        text = text.replace(Regex("\\s+"), " ").trim()

        return text
    }
}
