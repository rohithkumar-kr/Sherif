package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.SherifErrorMapper
import com.example.aiinterviewapp.data.remote.model.SherifErrorCode
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * A connection failure is the one AI error the user cannot fix by retrying, so
 * it has to say which of the two causes it is: a real outage, or a build that
 * was never pointed at a backend.
 *
 * The body-parsing cases matter just as much. The server has always sent a code,
 * but the client used to ignore it and infer everything from the status line --
 * which cannot tell a rate limit from an upstream outage, and cannot express a
 * misconfigured server at all.
 */
class SherifErrorMapperTest {

    @Test
    fun `an outage is described as a temporary service failure`() {
        assertEquals(
            "The AI service is temporarily unavailable.",
            SherifErrorMapper.connectionFailureMessage(apiConfigured = true)
        )
    }

    @Test
    fun `an unconfigured build names the setting that has to change`() {
        val message = SherifErrorMapper.connectionFailureMessage(apiConfigured = false)

        assertTrue(message.contains("SHERIF_API_BASE_URL"))
    }

    @Test
    fun `an unreachable backend still maps to the upstream-unavailable code`() {
        val mapped = SherifErrorMapper.map(IOException("no route to host"))

        assertEquals(SherifErrorCode.UPSTREAM_UNAVAILABLE, mapped.code)
    }

    @Test
    fun `the code in the body is used rather than guessed from the status`() {
        // The server serialises its own enum names, which is why the mapper
        // compares UPPER_SNAKE rather than relying on the client enum's
        // lower_snake @SerialName.
        val mapped = SherifErrorMapper.map(httpException(429, """{"error":{"code":"RATE_LIMITED"}}"""))

        assertEquals(SherifErrorCode.RATE_LIMITED, mapped.code)
    }

    @Test
    fun `a Gemini quota refusal is distinguishable from a server outage`() {
        val mapped = SherifErrorMapper.map(httpException(500, """{"error":{"code":"NOT_CONFIGURED"}}"""))

        assertEquals(SherifErrorCode.NOT_CONFIGURED, mapped.code)
        assertFalse(mapped.message.contains("API key"))
    }

    @Test
    fun `an upstream rejection is not described as a temporary outage`() {
        val mapped = SherifErrorMapper.map(httpException(502, """{"error":{"code":"UPSTREAM_REJECTED"}}"""))

        assertEquals(SherifErrorCode.UPSTREAM_REJECTED, mapped.code)
    }

    @Test
    fun `an identity outage is not confused with a session expiry`() {
        val mapped = SherifErrorMapper.map(
            httpException(503, """{"error":{"code":"IDENTITY_UNAVAILABLE"}}""")
        )

        assertEquals(SherifErrorCode.IDENTITY_UNAVAILABLE, mapped.code)
        assertFalse(mapped.requiresSignIn)
    }

    @Test
    fun `an expired session is flagged for re-authentication`() {
        val mapped = SherifErrorMapper.map(
            httpException(401, """{"error":{"code":"SESSION_EXPIRED"}}""")
        )

        assertEquals(SherifErrorCode.SESSION_EXPIRED, mapped.code)
        assertTrue(mapped.requiresSignIn)
    }

    @Test
    fun `a missing session is flagged for re-authentication`() {
        val mapped = SherifErrorMapper.map(
            httpException(401, """{"error":{"code":"UNAUTHENTICATED"}}""")
        )

        assertTrue(mapped.requiresSignIn)
    }

    @Test
    fun `an empty body falls back to the status rather than failing`() {
        val mapped = SherifErrorMapper.map(httpException(429, ""))

        assertEquals(SherifErrorCode.RATE_LIMITED, mapped.code)
    }

    @Test
    fun `a body that is not the expected shape still maps by status`() {
        val mapped = SherifErrorMapper.map(httpException(503, "<html>gateway timeout</html>"))

        assertEquals(SherifErrorCode.UPSTREAM_UNAVAILABLE, mapped.code)
    }

    @Test
    fun `a body with an unexpected shape still maps by status`() {
        val mapped = SherifErrorMapper.map(httpException(404, """{"error":{"code":42}}"""))

        assertEquals(SherifErrorCode.MALFORMED_REQUEST, mapped.code)
    }

    @Test
    fun `a code this build has never heard of degrades to the status`() {
        val mapped = SherifErrorMapper.map(
            httpException(502, """{"error":{"code":"A_CODE_FROM_THE_FUTURE"}}""")
        )

        assertEquals(SherifErrorCode.UPSTREAM_REJECTED, mapped.code)
    }

    @Test
    fun `the status distinguishes an upstream refusal from an outage`() {
        assertEquals(SherifErrorCode.UPSTREAM_REJECTED, SherifErrorMapper.codeForStatus(502))
        assertEquals(SherifErrorCode.UPSTREAM_UNAVAILABLE, SherifErrorMapper.codeForStatus(503))
    }

    @Test
    fun `an unrecognised throwable never leaks its own message`() {
        val mapped = SherifErrorMapper.map(
            IllegalStateException("connect ECONNREFUSED 10.0.0.5:8080")
        )

        assertFalse(mapped.message.contains("10.0.0.5"))
        assertEquals(SherifErrorCode.INTERNAL_ERROR, mapped.code)
    }

    private fun httpException(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType()))
    )
}
