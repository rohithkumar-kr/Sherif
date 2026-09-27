package com.example.aiinterviewapp.domain.usecase

import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import com.example.aiinterviewapp.domain.repository.InterviewRepository
import javax.inject.Inject

class EvaluateAnswerUseCase @Inject constructor(
    private val repository: InterviewRepository
) {
    suspend operator fun invoke(question: String, answer: String): Result<QuestionEvaluation> {
        return repository.evaluateAnswer(question, answer)
    }
}
