package com.example.aiinterviewapp.data.ocr

import com.example.aiinterviewapp.domain.model.ResumeExtractionException

/**
 * A lightweight, deterministic quality gate applied to recognised text.
 *
 * Its only job is to reject output that clearly is not a resume — empty
 * strings, or page furniture such as `...///---` — so a hopeless upload never
 * reaches Gemini and never burns an API call. The thresholds are deliberately
 * loose: a legitimately short resume must still pass, so length alone is never
 * grounds for rejection. Content is judged mainly by how much of it is
 * actually alphabetic.
 */
object ResumeTextQualityValidator {

    /**
     * Minimum alphabetic characters required. A genuine resume always exceeds
     * this, even a very short one, whereas punctuation-only noise never does.
     */
    const val MIN_ALPHABETIC_CHARS = 12

    /**
     * Minimum share of non-whitespace characters that must be alphabetic.
     * Real resume prose sits far above this; symbol debris sits far below.
     */
    const val MIN_ALPHABETIC_RATIO = 0.5

    /** No threshold rejects on total length alone; short resumes are valid. */

    /**
     * Checks normalized [text] and returns it when usable.
     *
     * @throws ResumeExtractionException.PoorOcrQuality when the text cannot be
     *   trusted to describe a resume.
     */
    fun requireUsable(text: String): String {
        if (!isUsable(text)) {
            throw ResumeExtractionException.PoorOcrQuality()
        }
        return text
    }

    /** True when [text] carries enough real content to be worth analysing. */
    fun isUsable(text: String): Boolean = metrics(text).isUsable

    /** The individual signals behind [isUsable], exposed for tests and logging-free diagnostics. */
    fun metrics(text: String): QualityMetrics {
        if (text.isBlank()) {
            return QualityMetrics(
                totalChars = text.length,
                alphabeticChars = 0,
                alphabeticRatio = 0.0,
                isUsable = false
            )
        }
        val alphabetic = text.count { it.isLetter() }
        val ratio = alphabetic.toDouble() / text.length.toDouble()
        return QualityMetrics(
            totalChars = text.length,
            alphabeticChars = alphabetic,
            alphabeticRatio = ratio,
            isUsable = alphabetic >= MIN_ALPHABETIC_CHARS && ratio >= MIN_ALPHABETIC_RATIO
        )
    }

    data class QualityMetrics(
        val totalChars: Int,
        val alphabeticChars: Int,
        val alphabeticRatio: Double,
        val isUsable: Boolean
    )
}
