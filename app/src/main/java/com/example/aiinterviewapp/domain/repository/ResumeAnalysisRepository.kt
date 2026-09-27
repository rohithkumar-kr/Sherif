package com.example.aiinterviewapp.domain.repository

import com.example.aiinterviewapp.domain.model.ResumeAnalysis
import com.example.aiinterviewapp.domain.model.ResumeProfile
import kotlinx.coroutines.flow.Flow

/**
 * Resume analysis, kept as its own operation and its own repository so it can
 * never be satisfied by the interview question pipeline.
 */
interface ResumeAnalysisRepository {

    /**
     * Analyses [resumeText] with Gemini and returns a grounded [ResumeProfile].
     *
     * Returns a failure — never a fabricated or empty profile — when Gemini
     * cannot be reached, answers with something that is not a JSON object, or
     * returns a response with no usable content.
     */
    suspend fun analyzeResume(resumeText: String): Result<ResumeAnalysis>
}

/**
 * Durable storage for the most recent successful analysis.
 */
interface ResumeProfileStore {

    fun resumeProfile(): Flow<ResumeProfile?>

    suspend fun saveResumeProfile(profile: ResumeProfile)

    suspend fun clearResumeProfile()
}
