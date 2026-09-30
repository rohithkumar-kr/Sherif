package com.example.aiinterviewapp.data.remote.api

import com.example.aiinterviewapp.data.remote.model.BackendSessionRequest
import com.example.aiinterviewapp.data.remote.model.BackendSessionResponse
import com.example.aiinterviewapp.data.remote.model.GeminiRequest
import com.example.aiinterviewapp.data.remote.model.GeminiResponse
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * SHERIF's own backend, not Google.
 *
 * The app holds no Gemini credential. Every AI call is an authenticated request
 * to a server that owns the key, which is the only arrangement in which an
 * untrusted client cannot extract the secret (RULE 2, RULE 20).
 *
 * The three operations stay separate endpoints, mirroring the backend's own
 * separation, so resume analysis, question generation and answer evaluation
 * keep independent budgets and independent validation (RULE 3).
 */
interface SherifBackendApi {

    /**
     * Exchanges a Google ID token for a SHERIF session.
     *
     * The only unauthenticated call. It cannot produce a session without a
     * token Google signed, and the returned `userId` is the backend's own
     * reading of the verified identity, not something the client asked for.
     */
    @POST("v1/auth/session")
    suspend fun createSession(
        @Body request: BackendSessionRequest
    ): BackendSessionResponse

    /**
     * Asks a development backend for the development session.
     *
     * Only ever called when [com.example.aiinterviewapp.utils.DevAuthPolicy]
     * allows it, i.e. from a debug build, and it only succeeds against a
     * backend started with `SHERIF_DEV_AUTH_ENABLED=true`. Everywhere else the
     * server does not have this path and answers 404.
     *
     * Sends no body: there is nothing to ask for, and a request field would be
     * something a future change could start trusting.
     */
    @POST("v1/auth/dev-session")
    suspend fun createDevelopmentSession(): BackendSessionResponse

    @POST("v1/resume/analyze")
    suspend fun analyzeResume(
        @Body request: GeminiRequest
    ): GeminiResponse

    @POST("v1/interview/question")
    suspend fun generateQuestion(
        @Body request: GeminiRequest
    ): GeminiResponse

    @POST("v1/interview/evaluate")
    suspend fun evaluateAnswer(
        @Body request: GeminiRequest
    ): GeminiResponse
}
