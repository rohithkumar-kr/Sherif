package com.example.aiinterviewapp.data.ocr

/**
 * Deterministic clean-up of raw OCR output.
 *
 * OCR text is noisy in predictable ways: mixed line endings, stray tabs and
 * non-breaking spaces, trailing padding, and long runs of blank lines left
 * behind by page-layout detection. Everything here is a formatting rule fixed
 * in code. No language model is involved, because a model asked to "tidy" OCR
 * output would quietly rewrite facts, which is exactly what must not happen.
 *
 * The normaliser is deliberately conservative: it only collapses whitespace
 * and line structure. It never adds, removes or reorders words, digits or
 * punctuation, so `C++` stays `C++`, `3.5 years` stays `3.5 years`, and a year
 * is never altered.
 */
object OcrTextNormalizer {

    /**
     * Normalizes raw OCR text into the canonical resume text representation.
     *
     * Steps, in order:
     *  1. remove zero-width characters, which are rendering artefacts with no
     *     textual meaning;
     *  2. unify `\r\n` and lone `\r` to `\n`, and page breaks to newlines;
     *  3. convert tabs and exotic spaces to a plain space;
     *  4. collapse runs of spaces within a line;
     *  5. trim each line, so OCR indentation does not skew quality checks;
     *  6. collapse three or more consecutive newlines to a single blank line,
     *     preserving meaningful section boundaries;
     *  7. trim leading and trailing blank lines.
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""

        val withoutZeroWidth = raw.filterNot { it in ZERO_WIDTH_CHARS }

        val unifiedNewlines = withoutZeroWidth
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u000C', '\n')

        val spaceConverted = unifiedNewlines
            .map { if (it in EXOTIC_SPACES) ' ' else it }
            .joinToString("")

        val lines = spaceConverted
            .split('\n')
            .map { line -> line.replace(REPEATED_SPACES, " ").trim() }

        return lines
            .joinToString("\n")
            .replace(EXCESS_BLANK_LINES, "\n\n")
            .trim('\n')
    }

    /**
     * Zero-width space, zero-width non-joiner, zero-width joiner, word joiner
     * and the byte order mark: rendering artefacts that carry no textual
     * meaning.
     *
     * Spelled out as code points rather than as literal invisible characters,
     * because an invisible character in source is unreviewable and silently
     * easy to corrupt in a diff.
     */
    private val ZERO_WIDTH_CHARS: Set<Char> = setOf(
        0x200B, // zero width space
        0x200C, // zero width non-joiner
        0x200D, // zero width joiner
        0x2060, // word joiner
        0xFEFF  // byte order mark
    ).map { it.toChar() }.toSet()

    /**
     * Tab, no-break space, thin space, narrow no-break space, figure space and
     * ideographic space. OCR emits these for layout; they all mean a plain
     * space here.
     */
    private val EXOTIC_SPACES: Set<Char> = setOf(
        0x0009, // tab
        0x00A0, // no-break space
        0x2009, // thin space
        0x202F, // narrow no-break space
        0x2007, // figure space
        0x3000  // ideographic space
    ).map { it.toChar() }.toSet()

    /** Two or more spaces: OCR emits these for visual spacing, not meaning. */
    private val REPEATED_SPACES = Regex(" {2,}")

    /** Three or more newlines, i.e. more than one blank line in a row. */
    private val EXCESS_BLANK_LINES = Regex("\n{3,}")
}
