package com.example.aiinterviewapp.domain.usecase

import com.example.aiinterviewapp.domain.model.ResumeAnalysis
import com.example.aiinterviewapp.domain.repository.ResumeAnalysisRepository
import javax.inject.Inject

/**
 * Analyses resume text with Gemini.
 *
 * Returns a failure rather than an empty or default profile, so a caller can
 * never mistake a failed analysis for a successful one.
 */
class AnalyzeResumeUseCase @Inject constructor(
    private val repository: ResumeAnalysisRepository
) {
    suspend operator fun invoke(resumeText: String): Result<ResumeAnalysis> {
        return repository.analyzeResume(resumeText)
    }
}
