package com.example.aiinterviewapp.data.remote

import com.example.aiinterviewapp.domain.model.QuestionEvaluation
import com.example.aiinterviewapp.domain.model.normalized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Robust helpers for interpreting free-form LLM responses.
 *
 * Gemini output must never be trusted blindly: it can arrive wrapped in
 * markdown code fences, surrounded by prose, contain trailing punctuation,
 * or include out-of-range values. All parsing lives here so it is testable
 * and cannot crash the interview flow.
 */
object AiResponseParser {

    private val markdownFenceRegex = Regex("```(?:json)?\\s*|\\s*```", RegexOption.IGNORE_CASE)
    private val questionPrefixRegex = Regex("^\\s*(?:\\d+[.)]\\s*|[-*]\\s*|Question\\s*\\d*[:.]?\\s*|Q\\s*[:.]?\\s*)")

    /** Extracts the outermost JSON object from arbitrary LLM text, or null if none exists. */
    fun extractJsonObject(raw: String?): String? {
        if (raw == null) return null
        val cleaned = markdownFenceRegex.replace(raw, "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return cleaned.substring(start, end + 1).trim()
    }

    /**
     * Parses and validates an evaluation from LLM text.
     * Fails with a descriptive exception when no valid JSON object is present.
     */
    fun parseEvaluation(raw: String?, json: Json): Result<QuestionEvaluation> = runCatching {
        val jsonObject = extractJsonObject(raw)
            ?: throw IllegalArgumentException("AI response did not contain a JSON object")
        json.decodeFromString<QuestionEvaluation>(jsonObject).normalized()
    }

    /**
     * Cleans raw question text: strips markdown code fences, numbering prefixes,
     * bullet markers and bold markers. Returns null if nothing meaningful remains.
     */
    fun cleanQuestionText(raw: String?): String? {
        if (raw == null) return null
        var text = raw.trim()
        if (text.isEmpty()) return null
        text = markdownFenceRegex.replace(text, "").trim()
        text = questionPrefixRegex.replace(text, "")
        text = text.replace("**", "").replace("__", "")
        return text.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Extracts a single question from a response that may be either plain text
     * or a JSON object like {"question": "..."}. Returns null when nothing
     * meaningful remains.
     */
    fun parseQuestion(raw: String?, json: Json): String? {
        if (raw == null) return null
        val jsonObject = extractJsonObject(raw)
        if (jsonObject != null) {
            runCatching {
                val obj = json.parseToJsonElement(jsonObject).jsonObject
                val field = obj["question"]
                if (field is JsonPrimitive) {
                    field.content.trim().takeIf { it.isNotEmpty() }?.let { return cleanQuestionText(it) }
                }
            }
        }
        return cleanQuestionText(raw)
    }
}