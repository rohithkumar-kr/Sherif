package com.example.aiinterviewapp.domain.usecase

import com.example.aiinterviewapp.domain.repository.InterviewRepository
import javax.inject.Inject

class GenerateQuestionUseCase @Inject constructor(
    private val repository: InterviewRepository
) {
    suspend operator fun invoke(
        role: String,
        experience: String,
        difficulty: String,
        type: String,
        previousQuestions: List<String>,
        resumeContext: String? = null,
        lastAnswer: String? = null,
        questionIndex: Int = 0,
        totalQuestions: Int = 10
    ): Result<String> {
        return repository.generateQuestion(
            role, experience, difficulty, type, previousQuestions, resumeContext, lastAnswer,
            questionIndex, totalQuestions
        )
    }
}
