package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.AiResponseParser
import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import com.example.aiinterviewapp.domain.model.normalized
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseParserTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Test
    fun `cleanQuestionText strips numbering and markdown`() {
        assertEquals(
            "Explain how LiveData works.",
            AiResponseParser.cleanQuestionText("1. **Explain how LiveData works.**")
        )
    }

    @Test
    fun `cleanQuestionText handles blank or null`() {
        assertNull(AiResponseParser.cleanQuestionText(null))
        assertNull(AiResponseParser.cleanQuestionText("   "))
        assertNull(AiResponseParser.cleanQuestionText("```"))
    }

    @Test
    fun `extractJsonObject removes markdown fences and prose`() {
        val raw = "Here is the evaluation:\n```json\n{\"score\": 8}\n```\nHope it helps."
        assertEquals("{\"score\": 8}", AiResponseParser.extractJsonObject(raw))
    }

    @Test
    fun `extractJsonObject returns null when no object present`() {
        assertNull(AiResponseParser.extractJsonObject("no json here"))
        assertNull(AiResponseParser.extractJsonObject(null))
    }

    @Test
    fun `parseEvaluation clamps out-of-range scores`() {
        val raw = """{"score":15,"confidence":-3,"communication":7,"technical":10,"grammar":5,"suggestions":"s","strengths":"st","weaknesses":"w"}"""
        val evaluation = AiResponseParser.parseEvaluation(raw, json).getOrThrow()
        assertEquals(10, evaluation.score)
        assertEquals(0, evaluation.confidence)
        assertEquals(7, evaluation.communication)
        assertEquals(10, evaluation.technical)
    }

    @Test
    fun `parseEvaluation handles markdown wrapped JSON`() {
        val raw = "```json\n{\"score\":6,\"confidence\":5,\"communication\":4,\"technical\":6,\"grammar\":5,\"suggestions\":\"a\",\"strengths\":\"b\",\"weaknesses\":\"c\"}\n```"
        val evaluation = AiResponseParser.parseEvaluation(raw, json).getOrThrow()
        assertEquals(6, evaluation.score)
        assertEquals("a", evaluation.suggestions)
    }

    @Test
    fun `parseEvaluation fails gracefully on invalid input`() {
        val result = AiResponseParser.parseEvaluation("not json", json)
        assertTrue(result.isFailure)
    }

    @Test
    fun `normalized trims string fields`() {
        val evaluation = QuestionEvaluation(
            score = 5, confidence = 5, communication = 5, technical = 5, grammar = 5,
            suggestions = "  use more examples  ", strengths = " clear ", weaknesses = " too brief "
        )
        val normalized = evaluation.normalized()
        assertEquals("use more examples", normalized.suggestions)
        assertEquals("clear", normalized.strengths)
        assertEquals("too brief", normalized.weaknesses)
        assertNotNull(normalized)
    }

    @Test
    fun `parseQuestion extracts question from JSON object`() {
        val raw = """{"question": "Explain how LiveData works."}"""
        assertEquals("Explain how LiveData works.", AiResponseParser.parseQuestion(raw, json))
    }

    @Test
    fun `parseQuestion falls back to plain text`() {
        assertEquals("Explain how LiveData works.", AiResponseParser.parseQuestion("Explain how LiveData works.", json))
    }

    @Test
    fun `parseQuestion handles markdown wrapped JSON with prefixes`() {
        val raw = "```json\n{\"question\": \"1. **Explain how LiveData works.**\"}\n```"
        assertEquals("Explain how LiveData works.", AiResponseParser.parseQuestion(raw, json))
    }

    @Test
    fun `parseQuestion returns null on blank input`() {
        assertNull(AiResponseParser.parseQuestion(null, json))
        assertNull(AiResponseParser.parseQuestion("   ", json))
        assertNull(AiResponseParser.parseQuestion("```", json))
    }

    @Test
    fun `cleanQuestionText strips Q-prefixes`() {
        assertEquals(
            "Walk me through a project you are proud of.",
            AiResponseParser.cleanQuestionText("Q: Walk me through a project you are proud of.")
        )
        assertEquals(
            "Tell me about yourself.",
            AiResponseParser.cleanQuestionText("Question 3: Tell me about yourself.")
        )
        assertEquals(
            "What is your approach to debugging?",
            AiResponseParser.cleanQuestionText("- What is your approach to debugging?")
        )
    }

    @Test
    fun `extractJsonObject ignores prose after the object`() {
        val raw = """{"score": 7} and the candidate did well overall."""
        assertEquals("{\"score\": 7}", AiResponseParser.extractJsonObject(raw))
    }

    @Test
    fun `parseEvaluation fails on missing required fields instead of crashing`() {
        val raw = """{"score":7,"confidence":6}"""
        val result = AiResponseParser.parseEvaluation(raw, json)
        assertTrue(result.isFailure)
    }

    @Test
    fun `parseEvaluation fails when scores are non-numeric`() {
        val raw = """{"score":"high","confidence":6,"communication":5,"technical":6,"grammar":5,"suggestions":"s","strengths":"st","weaknesses":"w"}"""
        val result = AiResponseParser.parseEvaluation(raw, json)
        assertTrue(result.isFailure)
    }

    @Test
    fun `parseEvaluation handles prose before and after json`() {
        val raw = """Here is the analysis: {"score":6,"confidence":5,"communication":4,"technical":6,"grammar":5,"suggestions":"a","strengths":"b","weaknesses":"c"} That is all."""
        val evaluation = AiResponseParser.parseEvaluation(raw, json).getOrThrow()
        assertEquals(6, evaluation.score)
    }

    @Test
    fun `parseQuestion returns clean text for json with extra whitespace`() {
        val raw = """{ "question": "  Explain coroutines.  " }"""
        assertEquals("Explain coroutines.", AiResponseParser.parseQuestion(raw, json))
    }

    @Test
    fun `extractJsonObject handles braces inside string values`() {
        val raw = """{"question": "Explain {a} and {b} in your own words."}"""
        assertEquals(
            "{\"question\": \"Explain {a} and {b} in your own words.\"}",
            AiResponseParser.extractJsonObject(raw)
        )
    }

    @Test
    fun `parseQuestion preserves braces inside question text`() {
        val raw = """{"question": "Explain {a} and {b}."}"""
        assertEquals("Explain {a} and {b}.", AiResponseParser.parseQuestion(raw, json))
    }

    @Test
    fun `extractJsonObject handles newlines inside string values`() {
        val raw = "{\"question\": \"line1\nline2\"}"
        assertEquals("{\"question\": \"line1\nline2\"}", AiResponseParser.extractJsonObject(raw))
    }
}