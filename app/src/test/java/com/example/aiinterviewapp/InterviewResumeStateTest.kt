package com.example.aiinterviewapp

import com.example.aiinterviewapp.domain.model.Interview
import com.example.aiinterviewapp.domain.model.InterviewQuestion
import com.example.aiinterviewapp.domain.model.InterviewResumeState
import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import com.example.aiinterviewapp.domain.model.resumeState
import org.junit.Assert.assertEquals
import org.junit.Test

class InterviewResumeStateTest {

    private fun evaluation(score: Int = 8) = QuestionEvaluation(
        score = score, confidence = 8, communication = 8, technical = 8, grammar = 8,
        suggestions = "s", strengths = "st", weaknesses = "w"
    )

    private fun interview(questions: List<InterviewQuestion>): Interview {
        return Interview(
            id = "id-1",
            role = "Android Developer",
            type = "Technical",
            difficulty = "Medium",
            questionCount = 5,
            experience = "Fresher",
            questions = questions
        )
    }

    @Test
    fun `empty interview requires first question`() {
        assertEquals(InterviewResumeState.LOAD_FIRST_QUESTION, interview(emptyList()).resumeState())
    }

    @Test
    fun `question without answer awaits input`() {
        val interview = interview(listOf(InterviewQuestion(id = "q1", question = "Question?")))
        assertEquals(InterviewResumeState.AWAITING_ANSWER, interview.resumeState())
    }

    @Test
    fun `answered question without evaluation needs evaluation`() {
        val interview = interview(
            listOf(InterviewQuestion(id = "q1", question = "Question?", answer = "Answer"))
        )
        assertEquals(InterviewResumeState.NEEDS_EVALUATION, interview.resumeState())
    }

    @Test
    fun `answered and evaluated question loads next`() {
        val interview = interview(
            listOf(InterviewQuestion(id = "q1", question = "Question?", answer = "Answer", evaluation = evaluation()))
        )
        assertEquals(InterviewResumeState.LOAD_NEXT_QUESTION, interview.resumeState())
    }

    @Test
    fun `all questions evaluated is ready to finish`() {
        val interview = interview(
            (1..5).map {
                InterviewQuestion(id = "q$it", question = "Question $it?", answer = "Answer", evaluation = evaluation())
            }
        )
        assertEquals(InterviewResumeState.READY_TO_FINISH, interview.resumeState())
    }

    @Test
    fun `blank last question is invalid`() {
        val interview = interview(
            listOf(
                InterviewQuestion(id = "q1", question = "Good?", answer = "Answer", evaluation = evaluation()),
                InterviewQuestion(id = "q2", question = "   ")
            )
        )
        assertEquals(InterviewResumeState.INVALID, interview.resumeState())
    }

    @Test
    fun `more questions than questionCount is invalid`() {
        val interview = interview(
            (1..6).map {
                InterviewQuestion(id = "q$it", question = "Question $it?", answer = "Answer", evaluation = evaluation())
            }
        )
        assertEquals(InterviewResumeState.INVALID, interview.resumeState())
    }

    @Test
    fun `zero questionCount is invalid`() {
        val interview = interview(emptyList()).copy(questionCount = 0)
        assertEquals(InterviewResumeState.INVALID, interview.resumeState())
    }
}