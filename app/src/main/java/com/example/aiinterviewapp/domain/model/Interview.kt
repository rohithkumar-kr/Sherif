package com.example.aiinterviewapp.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class Interview(
    val id: String = "",
    val role: String,
    val type: String,
    val difficulty: String,
    val questionCount: Int,
    val experience: String,
    val score: Int = 0,
    val date: Long = System.currentTimeMillis(),
    val status: InterviewStatus = InterviewStatus.STARTED,
    val questions: List<InterviewQuestion> = emptyList()
)

/**
 * Computes the overall interview score as a percentage (0-100) based on the
 * average of the per-question evaluation scores (each 0-10).
 * Returns 0 when there are no evaluated questions instead of crashing.
 */
fun Interview.overallScorePercent(): Int {
    val evaluatedScores = questions.mapNotNull { it.evaluation?.score }
    if (evaluatedScores.isEmpty()) return 0
    return (evaluatedScores.average() * 10).roundToInt().coerceIn(0, 100)
}

enum class InterviewResumeState {
    LOAD_FIRST_QUESTION,
    AWAITING_ANSWER,
    NEEDS_EVALUATION,
    LOAD_NEXT_QUESTION,
    READY_TO_FINISH,
    INVALID
}

/**
 * Pure function that determines what the next action is for a partially
 * completed interview, so a resumed session can be reconstructed safely.
 * Returns INVALID when the record is corrupted (missing config, too many
 * questions, or a malformed last question).
 */
fun Interview.resumeState(): InterviewResumeState {
    if (questionCount <= 0) return InterviewResumeState.INVALID
    if (questions.size > questionCount) return InterviewResumeState.INVALID
    val last = questions.lastOrNull()
        ?: return InterviewResumeState.LOAD_FIRST_QUESTION
    if (last.question.isBlank()) return InterviewResumeState.INVALID
    if (last.answer.isNullOrBlank()) return InterviewResumeState.AWAITING_ANSWER
    if (last.evaluation == null) return InterviewResumeState.NEEDS_EVALUATION
    return if (questions.size >= questionCount) {
        InterviewResumeState.READY_TO_FINISH
    } else {
        InterviewResumeState.LOAD_NEXT_QUESTION
    }
}

@Serializable
enum class InterviewStatus {
    STARTED, COMPLETED, CANCELLED
}

@Serializable
data class InterviewQuestion(
    val id: String,
    val question: String,
    val answer: String? = null,
    val evaluation: QuestionEvaluation? = null
)

@Serializable
data class QuestionEvaluation(
    val score: Int,
    val confidence: Int,
    val communication: Int,
    val technical: Int,
    val grammar: Int,
    val suggestions: String,
    val strengths: String,
    val weaknesses: String
)

/**
 * Validates and normalizes AI-produced evaluation data so out-of-range or
 * blank values can never break the UI or the scoring logic.
 */
fun QuestionEvaluation.normalized(): QuestionEvaluation = QuestionEvaluation(
    score = score.coerceIn(0, 10),
    confidence = confidence.coerceIn(0, 10),
    communication = communication.coerceIn(0, 10),
    technical = technical.coerceIn(0, 10),
    grammar = grammar.coerceIn(0, 10),
    suggestions = suggestions.trim(),
    strengths = strengths.trim(),
    weaknesses = weaknesses.trim()
)
