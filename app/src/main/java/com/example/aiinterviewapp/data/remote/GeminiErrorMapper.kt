package com.example.aiinterviewapp.data.remote

import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Maps Gemini API failures to clear, user-facing messages so the raw
 * HTTP status never leaks into the UI. The API key value is never included.
 */
object GeminiErrorMapper {

    fun map(t: Throwable): String {
        return when (t) {
            is HttpException -> when (t.code()) {
                400 -> "Gemini rejected the request format. Please try again."
                401 -> "Gemini authentication failed. Check that your API key is valid and enabled."
                403 -> "This Gemini API key is not allowed to use this model. Check your Google Cloud settings."
                404 -> "The Gemini model is unavailable. Please try again later."
                429 -> "You've hit the Gemini rate limit. Wait a moment and try again."
                in 500..599 -> "Gemini is temporarily unavailable. Please try again."
                else -> "Gemini request failed (HTTP ${t.code()}). Please try again."
            }
            is SerializationException -> "Gemini returned a response in an unexpected format. Please try again."
            is IOException -> "No internet connection. Check your network and try again."
            else -> t.message ?: "Something went wrong. Please try again."
        }
    }
}

/**
 * Replaces any failure with the user-facing message from [GeminiErrorMapper],
 * keeping the original as the cause. Shared by every Gemini-backed operation
 * so all of them surface identical wording.
 */
internal fun <T> Result<T>.withGeminiErrorMapping(): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { Result.failure(Exception(GeminiErrorMapper.map(it), it)) }
)