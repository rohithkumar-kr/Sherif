package com.sherif.backend.ai

import com.sherif.backend.BackendError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How an upstream refusal is reported to the app.
 *
 * The point of these cases is that a *server* fault and a *user* fault must not
 * look the same on the phone. When every 4xx became `UPSTREAM_REJECTED`, a
 * backend started with a dead API key told every user "The AI service rejected
 * the request. Please try again." forever, which is both untrue and unactionable
 * -- retrying cannot fix a credential.
 */
class GeminiStatusMappingTest {

    @Test
    fun `a rejected key is reported as a configuration fault, not a rejection`() {
        assertEquals(BackendError.NOT_CONFIGURED, HttpGeminiGateway.errorFor(401))
        assertEquals(BackendError.NOT_CONFIGURED, HttpGeminiGateway.errorFor(403))
    }

    @Test
    fun `an unknown model is reported as a configuration fault`() {
        assertEquals(BackendError.NOT_CONFIGURED, HttpGeminiGateway.errorFor(404))
    }

    @Test
    fun `the upstream quota is distinguishable from this server's own limiter`() {
        assertEquals(BackendError.RATE_LIMITED, HttpGeminiGateway.errorFor(429))
    }

    @Test
    fun `an upstream outage stays an outage`() {
        assertEquals(BackendError.UPSTREAM_UNAVAILABLE, HttpGeminiGateway.errorFor(500))
        assertEquals(BackendError.UPSTREAM_UNAVAILABLE, HttpGeminiGateway.errorFor(503))
    }

    @Test
    fun `a body the API rejected is an internal fault, not the user's`() {
        assertEquals(BackendError.INTERNAL, HttpGeminiGateway.errorFor(400))
    }

    @Test
    fun `other client errors remain a rejection rather than crashing the mapping`() {
        assertEquals(BackendError.UPSTREAM_REJECTED, HttpGeminiGateway.errorFor(422))
    }

    @Test
    fun `every upstream status maps to an error this API can actually return`() {
        (100..599).forEach { status ->
            val error = HttpGeminiGateway.errorFor(status)
            assertEquals(
                "status $status must map to a declared error",
                true,
                BackendError.entries.contains(error)
            )
        }
    }

    @Test
    fun `no upstream status is reported as something that would sign a user out`() {
        // An upstream problem must never invalidate a session. Doing so would
        // log every user out the first time Gemini rate-limited the server.
        (100..599).forEach { status ->
            val error = HttpGeminiGateway.errorFor(status)
            assertEquals(
                "status $status must not invalidate a session",
                false,
                error.invalidatesSession
            )
        }
    }
}
