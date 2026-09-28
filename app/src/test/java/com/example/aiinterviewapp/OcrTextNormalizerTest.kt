package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.ocr.OcrTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normalization must clean up OCR formatting without ever altering a fact.
 *
 * The "does not alter facts" cases are the important half: a normaliser that
 * tidies text by rewriting it is worse than no normaliser at all, because the
 * damage reaches Gemini as if it were fact.
 */
class OcrTextNormalizerTest {

    // --- Line endings and whitespace -------------------------------------

    @Test
    fun `windows line endings become unix line endings`() {
        assertEquals("a\nb\nc", OcrTextNormalizer.normalize("a\r\nb\r\nc"))
    }

    @Test
    fun `lone carriage returns become newlines`() {
        assertEquals("a\nb", OcrTextNormalizer.normalize("a\rb"))
    }

    @Test
    fun `form feed page breaks become newlines`() {
        assertEquals("a\nb", OcrTextNormalizer.normalize("a\u000Cb"))
    }

    @Test
    fun `tabs and exotic spaces become ordinary spaces`() {
        assertEquals("Kotlin Room", OcrTextNormalizer.normalize("Kotlin\tRoom"))
        assertEquals("Kotlin Room", OcrTextNormalizer.normalize("Kotlin\u00A0Room"))
        assertEquals("Kotlin Room", OcrTextNormalizer.normalize("Kotlin\u2009Room"))
    }

    @Test
    fun `runs of spaces within a line collapse to one`() {
        assertEquals("Android Developer", OcrTextNormalizer.normalize("Android     Developer"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Kotlin", OcrTextNormalizer.normalize("   Kotlin   "))
    }

    @Test
    fun `indentation used for visual layout is removed`() {
        assertEquals("Kotlin", OcrTextNormalizer.normalize("        Kotlin"))
    }

    @Test
    fun `zero width characters are removed`() {
        assertEquals("Kotlin", OcrTextNormalizer.normalize("Kot\u200Blin"))
        assertEquals("Kotlin", OcrTextNormalizer.normalize("\uFEFFKotlin"))
    }

    @Test
    fun `repeated blank lines collapse to a single blank line`() {
        assertEquals("A\n\nB", OcrTextNormalizer.normalize("A\n\n\n\n\nB"))
    }

    @Test
    fun `a single blank line between sections is preserved`() {
        assertEquals("SKILLS\n\nEDUCATION", OcrTextNormalizer.normalize("SKILLS\n\nEDUCATION"))
    }

    @Test
    fun `leading and trailing blank lines are removed`() {
        assertEquals("A\n\nB", OcrTextNormalizer.normalize("\n\n\nA\n\nB\n\n\n"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", OcrTextNormalizer.normalize(""))
    }

    @Test
    fun `whitespace only input normalizes to empty`() {
        assertEquals("", OcrTextNormalizer.normalize("   \n\n\t  "))
    }

    // --- Facts must survive untouched (Phase 2, do-not-alter-facts) --------

    @Test
    fun `C plus plus is never reduced to C`() {
        val normalized = OcrTextNormalizer.normalize("Skills:   C++,   C#")

        assertTrue("C++ must survive normalization", normalized.contains("C++"))
        assertFalse("the plus signs must not be dropped", normalized.contains(" C ,"))
    }

    @Test
    fun `a decimal number keeps its decimal point`() {
        assertTrue(
            OcrTextNormalizer.normalize("Experience: 3.5   years")
                .contains("3.5")
        )
    }

    @Test
    fun `years are never altered`() {
        val normalized = OcrTextNormalizer.normalize("2019  2021   2024")

        assertEquals("2019 2021 2024", normalized)
    }

    @Test
    fun `email addresses survive normalization`() {
        assertTrue(
            OcrTextNormalizer.normalize("aarav.sharma@example.com")
                .contains("aarav.sharma@example.com")
        )
    }

    @Test
    fun `word order and count are preserved exactly`() {
        val raw = "John Smith\nAndroid Developer\nKotlin\nJetpack Compose"
        val normalized = OcrTextNormalizer.normalize(raw)

        assertEquals(
            raw.split("\n").map { it.trim() },
            normalized.split("\n")
        )
    }

    @Test
    fun `punctuation that carries meaning is preserved`() {
        val normalized = OcrTextNormalizer.normalize("B.Tech,  Anna University - 2019")

        assertTrue(normalized.contains("B.Tech,"))
        assertTrue(normalized.contains("University - 2019"))
    }

    @Test
    fun `normalization is idempotent`() {
        val once = OcrTextNormalizer.normalize(OcrTestDoubles.NOISY_OCR_TEXT)

        assertEquals("normalizing twice must change nothing", once, OcrTextNormalizer.normalize(once))
    }
}
