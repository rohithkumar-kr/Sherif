package com.example.aiinterviewapp.data.repository

import com.example.aiinterviewapp.data.remote.AiResponseParser
import com.example.aiinterviewapp.data.remote.ResumeAnalysisPrompts
import com.example.aiinterviewapp.data.remote.ResumeGrounding
import com.example.aiinterviewapp.data.remote.api.GeminiApi
import com.example.aiinterviewapp.data.remote.model.GeminiRequest
import com.example.aiinterviewapp.data.remote.model.GeminiSchemas
import com.example.aiinterviewapp.data.remote.model.getText
import com.example.aiinterviewapp.data.remote.withGeminiErrorMapping
import com.example.aiinterviewapp.domain.model.ResumeAnalysis
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.hasContent
import com.example.aiinterviewapp.domain.repository.ResumeAnalysisRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resume analysis against Gemini.
 *
 * This is a standalone operation with its own prompt, its own response schema
 * and its own model type. It shares only the HTTP client with question
 * generation and never routes through it.
 */
@Singleton
class ResumeAnalysisRepositoryImpl @Inject constructor(
    private val api: GeminiApi,
    private val json: Json
) : ResumeAnalysisRepository {

    override suspend fun analyzeResume(resumeText: String): Result<ResumeAnalysis> = runCatching {
        if (resumeText.isBlank()) {
            throw IllegalArgumentException("There is no resume text to analyze.")
        }

        val response = api.generateContent(
            GeminiRequest.create(
                ResumeAnalysisPrompts.build(resumeText),
                temperature = 0.2,
                maxOutputTokens = 4096,
                responseMimeType = "application/json",
                responseSchema = GeminiSchemas.resumeProfileSchema
            )
        )

        val raw = response.getText()
            ?: throw IllegalStateException("Gemini returned an empty response. Please try again.")

        val jsonObject = AiResponseParser.extractJsonObject(raw)
            ?: throw IllegalStateException(
                "Gemini returned a response that could not be read as a resume analysis. Please try again."
            )

        val decoded = json.decodeFromString<ResumeProfile>(jsonObject)

        val (grounded, report) = ResumeGrounding.ground(decoded, resumeText)
        if (!grounded.hasContent) {
            throw IllegalStateException(
                "Gemini did not find any verifiable information in this resume. Please try again."
            )
        }
        ResumeAnalysis(profile = grounded, unsupportedClaims = report.unsupportedClaims)
    }.withGeminiErrorMapping()
}
