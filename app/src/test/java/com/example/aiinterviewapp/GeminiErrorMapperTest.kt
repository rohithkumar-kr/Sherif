package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.GeminiErrorMapper
import java.io.IOException
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class GeminiErrorMapperTest {

    private fun httpException(code: Int): HttpException {
        return HttpException(Response.error<Any>(code, "{}".toResponseBody()))
    }

    @Test
    fun `maps bad request to friendly message`() {
        assertEquals(
            "Gemini rejected the request format. Please try again.",
            GeminiErrorMapper.map(httpException(400))
        )
    }

    @Test
    fun `maps unauthorized to api key message`() {
        assertEquals(
            "Gemini authentication failed. Check that your API key is valid and enabled.",
            GeminiErrorMapper.map(httpException(401))
        )
    }

    @Test
    fun `maps forbidden to model permission message`() {
        assertEquals(
            "This Gemini API key is not allowed to use this model. Check your Google Cloud settings.",
            GeminiErrorMapper.map(httpException(403))
        )
    }

    @Test
    fun `maps not found to unavailable message`() {
        assertEquals(
            "The Gemini model is unavailable. Please try again later.",
            GeminiErrorMapper.map(httpException(404))
        )
    }

    @Test
    fun `maps rate limit to wait message`() {
        assertEquals(
            "You've hit the Gemini rate limit. Wait a moment and try again.",
            GeminiErrorMapper.map(httpException(429))
        )
    }

    @Test
    fun `maps server errors to temporary message`() {
        assertEquals(
            "Gemini is temporarily unavailable. Please try again.",
            GeminiErrorMapper.map(httpException(500))
        )
        assertEquals(
            "Gemini is temporarily unavailable. Please try again.",
            GeminiErrorMapper.map(httpException(503))
        )
    }

    @Test
    fun `maps other http codes to generic message`() {
        assertEquals(
            "Gemini request failed (HTTP 418). Please try again.",
            GeminiErrorMapper.map(httpException(418))
        )
    }

    @Test
    fun `maps io exception to network message`() {
        assertEquals(
            "No internet connection. Check your network and try again.",
            GeminiErrorMapper.map(IOException("socket timeout"))
        )
    }

    @Test
    fun `maps serialization exception to unexpected format message`() {
        assertEquals(
            "Gemini returned a response in an unexpected format. Please try again.",
            GeminiErrorMapper.map(SerializationException("Field 'text' is required"))
        )
    }

    @Test
    fun `surfaces friendly messages from unknown errors`() {
        assertEquals(
            "Gemini returned an empty or unreadable question. Please try again.",
            GeminiErrorMapper.map(IllegalStateException("Gemini returned an empty or unreadable question. Please try again."))
        )
    }

    @Test
    fun `falls back to generic message when no message exists`() {
        assertEquals(
            "Something went wrong. Please try again.",
            GeminiErrorMapper.map(RuntimeException())
        )
    }
}