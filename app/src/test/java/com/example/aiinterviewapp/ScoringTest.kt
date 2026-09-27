package com.example.aiinterviewapp

import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewQuestion
import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import com.example.aiinterviewapp.domain.model.overallScorePercent
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoringTest {

    private fun interviewWithScores(scores: List<Int>): Interview {
        val questions = scores.map { score ->
            InterviewQuestion(
                id = "q$score",
                question = "Question $score",
                answer = "Answer",
                evaluation = QuestionEvaluation(
                    score = score,
                    confidence = 5,
                    communication = 5,
                    technical = 5,
                    grammar = 5,
                    suggestions = "",
                    strengths = "",
                    weaknesses = ""
                )
            )
        }
        return Interview(
            id = "i",
            role = "Android Developer",
            type = "Technical",
            difficulty = "Medium",
            questionCount = scores.size,
            experience = "Fresher",
            questions = questions
        )
    }

    @Test
    fun `overall score is average times ten as percentage`() {
        val interview = interviewWithScores(listOf(8, 6, 4))
        assertEquals(60, interview.overallScorePercent())
    }

    @Test
    fun `overall score returns 0 for no questions`() {
        val interview = interviewWithScores(emptyList())
        assertEquals(0, interview.overallScorePercent())
    }

    @Test
    fun `overall score ignores questions without evaluation`() {
        val interview = interviewWithScores(listOf(10, 10)).copy(
            questions = listOf(
                InterviewQuestion(id = "1", question = "q"),
                InterviewQuestion(id = "2", question = "q2", answer = "a")
            )
        )
        assertEquals(0, interview.overallScorePercent())
    }

    @Test
    fun `overall score is clamped to 100`() {
        val interview = interviewWithScores(listOf(10, 10))
        assertEquals(100, interview.overallScorePercent())
    }
}