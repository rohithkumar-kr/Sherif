package com.example.aiinterviewapp.data.remote.model

import kotlinx.serialization.Serializable

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate>
) {
    @Serializable
    data class Candidate(
        val content: Content
    )

    @Serializable
    data class Content(
        val parts: List<Part>
    )

    @Serializable
    data class Part(
        val text: String
    )
}

fun GeminiResponse.getText(): String? {
    return candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
}
