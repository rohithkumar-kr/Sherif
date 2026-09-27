package com.example.aiinterviewapp.domain.repository

import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewQuestion
import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import kotlinx.coroutines.flow.Flow

interface InterviewRepository {
    suspend fun generateQuestion(
        role: String,
        experience: String,
        difficulty: String,
        type: String,
        previousQuestions: List<String>,
        resumeContext: String? = null,
        lastAnswer: String? = null,
        questionIndex: Int = 0,
        totalQuestions: Int = 10
    ): Result<String>

    suspend fun evaluateAnswer(
        question: String,
        answer: String
    ): Result<QuestionEvaluation>

    suspend fun saveInterview(interview: Interview)
    fun getInterviewHistory(): Flow<List<Interview>>
    suspend fun getInterviewById(id: String): Interview?
    suspend fun getResumableInterview(): Interview?
    fun getResumableInterviewFlow(): Flow<Interview?>
    suspend fun deleteInterview(id: String)
}
