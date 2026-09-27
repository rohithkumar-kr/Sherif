package com.example.aiinterviewapp.data.remote.model

/**
 * responseSchema definitions for Gemini structured output.
 * Kept in one place so both question generation and evaluation share
 * the same schema, with AiResponseParser retained as a fallback for
 * models or responses that do not honor the schema.
 */
object GeminiSchemas {

    val stringSchema = GeminiRequest.Schema(type = "STRING")

    val integerSchema = GeminiRequest.Schema(type = "INTEGER")

    val questionSchema = GeminiRequest.Schema(
        type = "OBJECT",
        properties = mapOf("question" to stringSchema),
        required = listOf("question"),
        description = "The single interview question as plain text."
    )

    private fun arraySchema(item: GeminiRequest.Schema): GeminiRequest.Schema =
        GeminiRequest.Schema(type = "ARRAY", items = item)

    /**
     * Dedicated schema for the resume analysis operation.
     *
     * Kept separate from [questionSchema] and [evaluationSchema] so analysis
     * can never be satisfied by a question-shaped response. Contact details are
     * intentionally left out of `required`: a resume that omits them must stay
     * representable instead of forcing Gemini to invent a value.
     */
    val resumeProfileSchema = GeminiRequest.Schema(
        type = "OBJECT",
        properties = mapOf(
            "candidateName" to stringSchema,
            "candidateEmail" to stringSchema,
            "candidatePhone" to stringSchema,
            "targetRole" to stringSchema,
            "summary" to stringSchema,
            "technicalSkills" to arraySchema(stringSchema),
            "softSkills" to arraySchema(stringSchema),
            "education" to arraySchema(
                GeminiRequest.Schema(
                    type = "OBJECT",
                    properties = mapOf(
                        "institution" to stringSchema,
                        "degree" to stringSchema,
                        "field" to stringSchema,
                        "graduationYear" to stringSchema
                    )
                )
            ),
            "workExperience" to arraySchema(
                GeminiRequest.Schema(
                    type = "OBJECT",
                    properties = mapOf(
                        "company" to stringSchema,
                        "role" to stringSchema,
                        "duration" to stringSchema,
                        "description" to stringSchema
                    )
                )
            ),
            "projects" to arraySchema(
                GeminiRequest.Schema(
                    type = "OBJECT",
                    properties = mapOf(
                        "name" to stringSchema,
                        "technologies" to arraySchema(stringSchema),
                        "description" to stringSchema
                    )
                )
            ),
            "certifications" to arraySchema(stringSchema),
            "achievements" to arraySchema(stringSchema),
            "languages" to arraySchema(stringSchema),
            "strengths" to arraySchema(stringSchema),
            "areasForImprovement" to arraySchema(stringSchema),
            "missingInformation" to arraySchema(stringSchema),
            "resumeQuality" to GeminiRequest.Schema(
                type = "OBJECT",
                properties = mapOf(
                    "overallRating" to stringSchema,
                    "clarityRating" to stringSchema,
                    "impactRating" to stringSchema,
                    "notes" to stringSchema
                )
            )
        ),
        required = listOf(
            "candidateName", "summary", "technicalSkills", "softSkills",
            "education", "workExperience", "projects", "certifications",
            "achievements", "languages", "strengths", "areasForImprovement",
            "missingInformation", "resumeQuality"
        ),
        description = "A structured analysis of a candidate's resume, containing only facts " +
            "supported by the resume text."
    )

    val evaluationSchema = GeminiRequest.Schema(
        type = "OBJECT",
        properties = mapOf(
            "score" to integerSchema,
            "confidence" to integerSchema,
            "communication" to integerSchema,
            "technical" to integerSchema,
            "grammar" to integerSchema,
            "suggestions" to stringSchema,
            "strengths" to stringSchema,
            "weaknesses" to stringSchema
        ),
        required = listOf(
            "score", "confidence", "communication", "technical",
            "grammar", "suggestions", "strengths", "weaknesses"
        ),
        description = "A scored evaluation of the candidate's answer."
    )
}