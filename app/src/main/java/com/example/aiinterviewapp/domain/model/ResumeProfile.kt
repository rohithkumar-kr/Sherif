package com.example.aiinterviewapp.domain.model

import kotlinx.serialization.Serializable

/**
 * A structured, AI-derived representation of a candidate's resume.
 *
 * Every field is optional. A resume that omits certifications, languages or a
 * summary is representable without inventing placeholder values: absent
 * information is simply absent, and Gemini is asked to record genuine gaps in
 * [missingInformation] instead of guessing.
 *
 * Factual fields (skills, education, experience, projects, certifications,
 * achievements, languages) are additionally passed through
 * `ResumeGrounding` before they reach this model, so a hallucinated skill can
 * never be persisted as fact. Interpretive fields (summary, strengths,
 * areasForImprovement, resumeQuality) are Gemini's assessment and are not
 * treated as factual claims.
 */
@Serializable
data class ResumeProfile(
    val candidateName: String? = null,
    val candidateEmail: String? = null,
    val candidatePhone: String? = null,
    val targetRole: String? = null,
    val summary: String? = null,
    val technicalSkills: List<String> = emptyList(),
    val softSkills: List<String> = emptyList(),
    val education: List<ResumeEducation> = emptyList(),
    val workExperience: List<ResumeExperience> = emptyList(),
    val projects: List<ResumeProject> = emptyList(),
    val certifications: List<String> = emptyList(),
    val achievements: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val strengths: List<String> = emptyList(),
    val areasForImprovement: List<String> = emptyList(),
    val missingInformation: List<String> = emptyList(),
    val resumeQuality: ResumeQuality = ResumeQuality()
)

@Serializable
data class ResumeEducation(
    val institution: String? = null,
    val degree: String? = null,
    val field: String? = null,
    val graduationYear: String? = null
)

@Serializable
data class ResumeExperience(
    val company: String? = null,
    val role: String? = null,
    val duration: String? = null,
    val description: String? = null
)

@Serializable
data class ResumeProject(
    val name: String? = null,
    val technologies: List<String> = emptyList(),
    val description: String? = null
)

/**
 * Gemini's assessment of the document itself rather than of the candidate.
 * Ratings are descriptive on purpose; a numeric value here would imply a
 * precision the analysis does not have.
 */
@Serializable
data class ResumeQuality(
    val overallRating: String? = null,
    val clarityRating: String? = null,
    val impactRating: String? = null,
    val notes: String? = null
)

/**
 * A completed analysis plus anything the grounding layer had to discard.
 *
 * Rejected claims are reported rather than dropped silently, so the UI can
 * tell the user that Gemini mentioned something the document does not support.
 */
@Serializable
data class ResumeAnalysis(
    val profile: ResumeProfile,
    val unsupportedClaims: List<String> = emptyList()
)

/**
 * True when the analysis produced at least one piece of usable information.
 * An all-empty profile is treated as a failed analysis by callers so that a
 * blank result is never presented as a successful one.
 */
val ResumeProfile.hasContent: Boolean
    get() = listOf(
        candidateName, candidateEmail, candidatePhone, targetRole, summary
    ).any { !it.isNullOrBlank() } ||
        technicalSkills.isNotEmpty() ||
        softSkills.isNotEmpty() ||
        education.isNotEmpty() ||
        workExperience.isNotEmpty() ||
        projects.isNotEmpty() ||
        certifications.isNotEmpty() ||
        achievements.isNotEmpty() ||
        languages.isNotEmpty() ||
        strengths.isNotEmpty() ||
        areasForImprovement.isNotEmpty() ||
        resumeQuality.overallRating != null ||
        resumeQuality.notes != null

/**
 * Every factual token the profile asserts, used by tests and by callers that
 * need to prove two analyses genuinely differ.
 */
val ResumeProfile.factualClaims: List<String>
    get() = buildList {
        addAll(technicalSkills)
        addAll(softSkills)
        addAll(certifications)
        addAll(achievements)
        addAll(languages)
        education.forEach { entry ->
            entry.institution?.let { add(it) }
            entry.degree?.let { add(it) }
            entry.field?.let { add(it) }
        }
        workExperience.forEach { entry ->
            entry.company?.let { add(it) }
            entry.role?.let { add(it) }
        }
        projects.forEach { project ->
            project.name?.let { add(it) }
            addAll(project.technologies)
        }
    }
