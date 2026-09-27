package com.example.aiinterviewapp.data.remote.api

import com.example.aiinterviewapp.data.remote.model.GeminiRequest
import com.example.aiinterviewapp.data.remote.model.GeminiResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface GeminiApi {
    @POST("v1beta/models/gemini-flash-latest:generateContent")
    suspend fun generateContent(
        @Body request: GeminiRequest
    ): GeminiResponse
}
