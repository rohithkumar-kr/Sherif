package com.example.aiinterviewapp.data.remote.model

import kotlinx.serialization.Serializable

@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null
) {
    @Serializable
    data class Content(
        val parts: List<Part>
    )

    @Serializable
    data class Part(
        val text: String
    )

    @Serializable
    data class GenerationConfig(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
        val responseMimeType: String? = null,
        val responseSchema: Schema? = null
    )

    @Serializable
    data class Schema(
        val type: String,
        val properties: Map<String, Schema>? = null,
        val required: List<String>? = null,
        val items: Schema? = null,
        val description: String? = null
    )

    companion object {
        fun create(
            prompt: String,
            temperature: Double? = null,
            maxOutputTokens: Int? = null,
            responseMimeType: String? = null,
            responseSchema: Schema? = null
        ): GeminiRequest {
            val config = if (
                temperature != null ||
                maxOutputTokens != null ||
                responseMimeType != null ||
                responseSchema != null
            ) {
                GenerationConfig(
                    temperature = temperature,
                    maxOutputTokens = maxOutputTokens,
                    responseMimeType = responseMimeType,
                    responseSchema = responseSchema
                )
            } else {
                null
            }
            return GeminiRequest(
                contents = listOf(
                    Content(parts = listOf(Part(text = prompt)))
                ),
                generationConfig = config
            )
        }
    }
}