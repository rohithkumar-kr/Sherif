package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.ocr.ResumeTextQualityValidator
import com.example.aiinterviewapp.domain.model.ResumeExtractionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quality gate decides what is allowed to cost a Gemini call, so it has to
 * reject obvious debris while never punishing a genuinely short resume.
 */
class ResumeTextQualityValidatorTest {

    @Test
    fun `a meaningful resume is accepted`() {
        val text = "John Smith\nAndroid Developer\nKotlin\nJetpack Compose\nRoom"

        assertTrue(ResumeTextQualityValidator.isUsable(text))
    }

    @Test
    fun `empty text is rejected`() {
        assertFalse(ResumeTextQualityValidator.isUsable(""))
    }

    @Test
    fun `whitespace only text is rejected`() {
        assertFalse(ResumeTextQualityValidator.isUsable("    \n\n  \t "))
    }

    @Test
    fun `symbol debris is rejected`() {
        assertFalse(ResumeTextQualityValidator.isUsable("...///---"))
        assertFalse(ResumeTextQualityValidator.isUsable("|||===***"))
    }

    @Test
    fun `a bare page number is rejected`() {
        assertFalse(ResumeTextQualityValidator.isUsable("1 / 3"))
    }

    @Test
    fun `a short but legitimate resume is not rejected for being short`() {
        // Length alone must never disqualify a resume.
        val short = "Jane Doe\nEngineer\nKotlin"

        assertTrue("a short real resume must pass", ResumeTextQualityValidator.isUsable(short))
    }

    @Test
    fun `a single long word is accepted when it is real prose`() {
        assertTrue(
            ResumeTextQualityValidator.isUsable("Curriculum Vitae of an Android Developer")
        )
    }

    @Test
    fun `punctuation heavy technical text is still accepted`() {
        val text = "Skills: C++, C#, Java, SQL, HTML/CSS, Node.js, REST APIs, Git"

        assertTrue("meaningful punctuation must not disqualify", ResumeTextQualityValidator.isUsable(text))
    }

    @Test
    fun `numeric heavy text with enough words is accepted`() {
        val text = "Graduated 2019, 3.5 years experience, 40 percent improvement delivered"

        assertTrue(ResumeTextQualityValidator.isUsable(text))
    }

    @Test
    fun `requireUsable returns the text unchanged when acceptable`() {
        val text = "John Smith\nAndroid Developer\nKotlin\nRoom"

        assertEquals(text, ResumeTextQualityValidator.requireUsable(text))
    }

    @Test
    fun `requireUsable throws the poor quality failure for debris`() {
        val thrown = runCatching {
            ResumeTextQualityValidator.requireUsable("...///---")
        }.exceptionOrNull()

        assertTrue(
            "expected a poor quality failure",
            thrown is ResumeExtractionException.PoorOcrQuality
        )
        assertEquals(
            "The resume text could not be read reliably. Please upload a clearer image or PDF.",
            thrown?.message
        )
    }

    @Test
    fun `metrics report the underlying signals`() {
        val metrics = ResumeTextQualityValidator.metrics("John Smith Android Developer")

        assertEquals(28, metrics.totalChars)
        assertTrue(metrics.alphabeticChars > 0)
        assertTrue(metrics.alphabeticRatio in 0.0..1.0)
        assertTrue(metrics.isUsable)
    }

    @Test
    fun `thresholds are documented constants rather than magic numbers`() {
        // If these change, the documented contract changes with them.
        assertEquals(12, ResumeTextQualityValidator.MIN_ALPHABETIC_CHARS)
        assertEquals(0.5, ResumeTextQualityValidator.MIN_ALPHABETIC_RATIO, 0.0001)
    }
}
