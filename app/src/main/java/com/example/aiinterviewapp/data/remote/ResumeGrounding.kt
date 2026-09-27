package com.example.aiinterviewapp.data.remote

import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.ResumeProject

/**
 * Guards against Gemini inventing facts that are absent from the uploaded
 * document.
 *
 * A structured-output model is a strong prior that a token was mentioned, but
 * it is not a guarantee. This layer re-checks every *factual* claim against
 * the resume text that was actually sent, and drops claims with no support.
 *
 * Matching policy (word-level, never substring, so "AWS" cannot be satisfied by
 * "always" and "R" cannot be satisfied by "engineer"):
 *
 *  - A claim is checked against a set of whole-word tokens from the resume.
 *  - Claims of two significant tokens or fewer (skill-like, e.g. "Spring
 *    Boot") require **every** significant token to be present. This is the
 *    strict rule and is what rejects fabricated technologies.
 *  - Longer, descriptive claims (e.g. a project named "Japanese Vocabulary
 *    Android Application" against a resume saying "Japanese Vocabulary App")
 *    require **at least one** significant token, so that legitimate paraphrase
 *    does not erase a real project.
 *  - Tokens shorter than three characters are ignored, so "C", "R" and "Go"
 *    can never be validated on their own.
 *
 * Interpretive fields ([ResumeProfile.summary], strengths,
 * areasForImprovement, missingInformation and resumeQuality) are deliberately
 * excluded: they are Gemini's judgement about the document, not claims of fact
 * about the candidate, and grounding them would silently destroy legitimate
 * critique.
 */
object ResumeGrounding {

    private val stopWords = setOf(
        "and", "the", "with", "for", "from", "using", "use", "used", "into",
        "etc", "plus", "such", "other", "others", "than", "that", "this",
        "via", "per", "over", "under", "have", "has", "had", "been", "being",
        "will", "would", "can", "could", "should", "may", "might", "must"
    )

    private val separators = Regex("[^a-z0-9+#.]+")

    /** Token separators drop ".", so "B.Tech" yields "tech" and "Node.js" yields "node". */
    private val tokenSeparators = Regex("[^a-z0-9+#]+")

    /** Claims that were dropped because nothing in the resume supported them. */
    data class Report(
        val unsupportedClaims: List<String> = emptyList()
    ) {
        val hasUnsupportedClaims: Boolean get() = unsupportedClaims.isNotEmpty()
    }

    /**
     * Returns a copy of [profile] with ungrounded factual claims removed, plus
     * a [Report] naming what was removed so the caller can surface it rather
     * than silently losing information.
     */
    fun ground(profile: ResumeProfile, resumeText: String): Pair<ResumeProfile, Report> {
        val vocabulary = Vocabulary.of(resumeText)
        val rejected = mutableListOf<String>()

        fun keep(claim: String?): String? {
            val trimmed = claim?.trim()
            if (trimmed.isNullOrEmpty()) return null
            if (vocabulary.supports(trimmed)) return trimmed
            rejected += trimmed
            return null
        }

        fun keepList(values: List<String>): List<String> =
            values.mapNotNull { keep(it) }.distinct()

        val grounded = profile.copy(
            candidateName = profile.candidateName?.let { keep(it) },
            candidateEmail = profile.candidateEmail,
            candidatePhone = profile.candidatePhone,
            targetRole = profile.targetRole?.let { keep(it) },
            technicalSkills = keepList(profile.technicalSkills),
            softSkills = keepList(profile.softSkills),
            education = profile.education.mapNotNull { entry ->
                val institution = entry.institution?.let { keep(it) }
                val degree = entry.degree?.let { keep(it) }
                val field = entry.field?.let { keep(it) }
                if (institution == null && degree == null && field == null) {
                    null
                } else {
                    entry.copy(institution = institution, degree = degree, field = field)
                }
            },
            workExperience = profile.workExperience.mapNotNull { entry ->
                val company = entry.company?.let { keep(it) }
                val role = entry.role?.let { keep(it) }
                if (company == null && role == null) {
                    null
                } else {
                    entry.copy(company = company, role = role)
                }
            },
            projects = profile.projects.mapNotNull { project ->
                val name = project.name?.let { keep(it) }
                val technologies = keepList(project.technologies)
                if (name == null && technologies.isEmpty()) {
                    null
                } else {
                    ResumeProject(
                        name = name,
                        technologies = technologies,
                        description = project.description
                    )
                }
            },
            certifications = keepList(profile.certifications),
            achievements = keepList(profile.achievements),
            languages = keepList(profile.languages)
        )

        return grounded to Report(rejected.distinct())
    }

    /**
     * Whole-word view of the resume text. Tokens are produced once per analysis
     * and reused for every claim.
     */
    private class Vocabulary private constructor(
        private val tokens: Set<String>,
        private val phrases: Set<String>
    ) {
        fun supports(claim: String): Boolean {
            val normalized = normalize(claim)
            if (normalized.isEmpty()) return false
            val claimTokens = significantTokens(claim)
            if (claimTokens.isEmpty()) {
                // A claim made only of very short tokens (e.g. "C", "R") can
                // never be corroborated, so it is rejected rather than guessed.
                return false
            }
            if (normalized in phrases) return true
            val present = claimTokens.count { it in tokens }
            return if (claimTokens.size <= 2) present == claimTokens.size else present >= 1
        }

        companion object {
            fun of(text: String): Vocabulary {
                val normalizedText = normalize(text)
                val allTokens = normalizedText.split(' ').filter { it.isNotEmpty() }
                val words = allTokens
                    .flatMap { token -> token.split('.').filter { it.isNotEmpty() } }
                    .toSet()
                val phrases = buildSet {
                    addAll(allTokens)
                    allTokens.forEachIndexed { index, _ ->
                        for (size in 2..4) {
                            if (index + size <= allTokens.size) {
                                add(allTokens.subList(index, index + size).joinToString(" "))
                            }
                        }
                    }
                }
                return Vocabulary(words, phrases)
            }
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(separators, " ").trim().replace(Regex("\\s+"), " ")

    private fun significantTokens(value: String): List<String> =
        value.lowercase().replace(tokenSeparators, " ").trim()
            .split(' ')
            .filter { it.length >= 3 && it !in stopWords }
}
