package com.sherif.backend.ai

import com.sherif.backend.BackendError
import com.sherif.backend.BackendException
import com.sherif.backend.SherifJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resume text is the largest legitimate input by far: a long CV plus
 * instructions. Everything else is a prompt SHERIF itself builds, so it is far
 * smaller and a bigger body is an attack, not a user.
 *
 * Top-level rather than in a companion object because the enum entries below
 * reference them during their own initialisation, which happens before a
 * companion object is ready.
 */
const val MAX_RESUME_BODY = 256 * 1024
const val MAX_RESUME_PROMPT = 120_000
const val MAX_QUESTION_BODY = 32 * 1024
const val MAX_QUESTION_PROMPT = 16_000
const val MAX_EVALUATE_BODY = 32 * 1024
const val MAX_EVALUATE_PROMPT = 16_000
const val MAX_AUTH_BODY = 8 * 1024

/**
 * The three AI operations SHERIF performs, kept separate on purpose.
 *
 * Each is a distinct endpoint with its own budget, so the resume pipeline
 * cannot be used as a general-purpose prompt channel, and a compromised
 * question-generation path cannot spend the resume-analysis allowance.
 */
enum class AiOperation(
    val path: String,
    val maxBodyBytes: Int,
    val maxPromptChars: Int
) {
    RESUME_ANALYZE("/v1/resume/analyze", MAX_RESUME_BODY, MAX_RESUME_PROMPT),
    INTERVIEW_QUESTION("/v1/interview/question", MAX_QUESTION_BODY, MAX_QUESTION_PROMPT),
    INTERVIEW_EVALUATE("/v1/interview/evaluate", MAX_EVALUATE_BODY, MAX_EVALUATE_PROMPT);

    companion object {
        fun forPath(path: String): AiOperation? = entries.firstOrNull { it.path == path }
    }
}

/**
 * The validated shape of an AI request after parsing.
 *
 * Only the fields SHERIF understands survive validation, and the gateway
 * re-serialises from these alone. A client therefore cannot smuggle extra
 * top-level keys into the upstream Gemini call.
 */
data class ValidatedAiRequest(
    val prompt: String,
    val temperature: Double?,
    val maxOutputTokens: Int?,
    val responseMimeType: String?,
    val responseSchemaJson: String?
)

/**
 * Parses and validates an incoming AI request body.
 *
 * Rejects, in this order: an over-sized body, malformed JSON, a body that is
 * not an object, missing/empty `contents`, missing `parts`, a missing or blank
 * `text`, a prompt longer than the operation allows, and any request that is not
 * asking for structured JSON output.
 *
 * That last rule is the important one. All three operations parse their reply
 * with a fixed schema, so requiring a declared response schema stops these
 * endpoints being used as a free-form text generator against a metered key.
 */
object AiRequestValidator {

    fun validate(operation: AiOperation, body: ByteArray): ValidatedAiRequest {
        if (body.size > operation.maxBodyBytes) {
            throw BackendException(BackendError.PAYLOAD_TOO_LARGE)
        }

        val root = runCatching {
            SherifJson.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: throw BackendException(BackendError.MALFORMED_REQUEST)

        val prompt = extractPrompt(root)
        if (prompt.length > operation.maxPromptChars) {
            throw BackendException(BackendError.PAYLOAD_TOO_LARGE)
        }

        val config = root["generationConfig"] as? JsonObject
        val mimeType = config?.stringOrNull("responseMimeType")
        if (mimeType != "application/json") {
            throw BackendException(BackendError.MALFORMED_REQUEST)
        }
        val schema = config?.get("responseSchema") as? JsonObject
        if (schema == null || schema.isEmpty()) {
            throw BackendException(BackendError.MALFORMED_REQUEST)
        }

        return ValidatedAiRequest(
            prompt = prompt,
            temperature = config?.doubleOrNull("temperature"),
            maxOutputTokens = config?.intOrNull("maxOutputTokens"),
            responseMimeType = mimeType,
            responseSchemaJson = schema.toString()
        )
    }

    /**
     * Pulls `contents[0].parts[0].text`.
     *
     * SHERIF only ever sends a single-content, single-part prompt, so a body
     * with more is not a valid SHERIF request and is refused rather than
     * silently truncated to whichever fragment happened to parse.
     */
    private fun extractPrompt(root: JsonObject): String {
        val contents = root["contents"] as? JsonArray
            ?: throw BackendException(BackendError.MALFORMED_REQUEST)
        if (contents.size != 1) throw BackendException(BackendError.MALFORMED_REQUEST)

        val content = contents.firstOrNull() as? JsonObject
            ?: throw BackendException(BackendError.MALFORMED_REQUEST)
        val parts = content["parts"] as? JsonArray
            ?: throw BackendException(BackendError.MALFORMED_REQUEST)
        if (parts.size != 1) throw BackendException(BackendError.MALFORMED_REQUEST)

        val part = parts.firstOrNull() as? JsonObject
            ?: throw BackendException(BackendError.MALFORMED_REQUEST)
        val text = part.stringOrNull("text")
        if (text.isNullOrBlank()) throw BackendException(BackendError.MALFORMED_REQUEST)
        return text
    }
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.content

private fun JsonObject.doubleOrNull(name: String): Double? =
    (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonObject.intOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)?.content?.toIntOrNull()
