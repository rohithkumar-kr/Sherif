package com.sherif.backend

import com.sherif.backend.ai.AiOperation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RULE 4, RULE 12 and AC-04: who may reach the metered Gemini call, and what
 * happens to everyone else.
 */
class BackendAuthorizationTest {

    private lateinit var gateway: BackendTestDoubles.RecordingGeminiGateway
    private lateinit var harness: BackendTestHarness

    @Before
    fun setUp() {
        gateway = BackendTestDoubles.RecordingGeminiGateway()
        harness = BackendTestHarness(gateway = gateway)
    }

    @After
    fun tearDown() = harness.stop()

    // --- Unauthenticated callers are refused --------------------------------

    @Test
    fun `an unauthenticated ai request is rejected`() {
        val response = harness.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token = null)

        assertEquals(401, response.statusCode())
        assertEquals(BackendError.UNAUTHENTICATED.name, BackendTestHarness.errorCode(response))
    }

    @Test
    fun `an unauthenticated request never reaches gemini`() {
        harness.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token = null)

        assertEquals("no API call may be made for an unauthenticated request", 0, gateway.calls)
    }

    @Test
    fun `a garbage bearer token is rejected`() {
        val response = harness.post(AiOperation.INTERVIEW_QUESTION.path, BackendTestDoubles.aiRequestBody(), token = "not-a-real-token")

        assertEquals(401, response.statusCode())
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `an authorization header that is not a bearer scheme is rejected`() {
        val response = harness.postRawAuthorization(
            AiOperation.RESUME_ANALYZE.path,
            BackendTestDoubles.aiRequestBody(),
            authorization = "Basic dXNlcjpwYXNz"
        )

        assertEquals(401, response.statusCode())
        assertEquals(BackendError.UNAUTHENTICATED.name, BackendTestHarness.errorCode(response))
    }

    @Test
    fun `an empty bearer token is rejected`() {
        val response = harness.postRawAuthorization(
            AiOperation.RESUME_ANALYZE.path,
            BackendTestDoubles.aiRequestBody(),
            authorization = "Bearer "
        )

        assertEquals(401, response.statusCode())
        assertEquals(0, gateway.calls)
    }

    // --- Authenticated callers succeed ---------------------------------------

    @Test
    fun `a valid session may analyse a resume`() {
        val token = harness.sessionFor("user-a")

        val response = harness.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token)

        assertEquals(200, response.statusCode())
        assertEquals(1, gateway.calls)
    }

    @Test
    fun `each of the three operations is separately reachable`() {
        val token = harness.sessionFor("user-a")

        assertEquals(200, harness.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token).statusCode())
        assertEquals(200, harness.post(AiOperation.INTERVIEW_QUESTION.path, BackendTestDoubles.aiRequestBody(), token).statusCode())
        assertEquals(200, harness.post(AiOperation.INTERVIEW_EVALUATE.path, BackendTestDoubles.aiRequestBody(), token).statusCode())
        assertEquals(3, gateway.calls)
    }

    @Test
    fun `the gemini response body is returned verbatim`() {
        val token = harness.sessionFor("user-a")

        val response = harness.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token)

        assertEquals(BackendTestDoubles.RecordingGeminiGateway.DEFAULT_RESPONSE, response.body())
    }

    // --- Session lifecycle ---------------------------------------------------

    @Test
    fun `a session exchange with an unverifiable identity token is refused`() {
        val response = harness.post(
            SherifBackendServer.SESSION_PATH,
            """{"idToken":"clearly-not-google-signed"}""",
            token = null
        )

        assertEquals(401, response.statusCode())
        assertEquals(BackendError.IDENTITY_TOKEN_INVALID.name, BackendTestHarness.errorCode(response))
    }

    @Test
    fun `a session exchange without an id token is refused`() {
        val response = harness.post(SherifBackendServer.SESSION_PATH, """{}""", token = null)

        assertEquals(400, response.statusCode())
    }

    @Test
    fun `an expired session is reported as expired`() {
        var now = 1_700_000_000_000L
        val codec = com.sherif.backend.auth.SessionTokenCodec(
            BackendTestDoubles.testSigningKey(),
            clock = { now }
        )
        val expiring = BackendTestHarness(
            gateway = gateway,
            sessionCodec = codec,
            sessionTtlSeconds = 60
        )
        try {
            val token = expiring.sessionFor("user-a")
            now += 61_000

            val response = expiring.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token)

            assertEquals(401, response.statusCode())
            assertEquals(BackendError.SESSION_EXPIRED.name, BackendTestHarness.errorCode(response))
            assertEquals(0, gateway.calls)
        } finally {
            expiring.stop()
        }
    }

    @Test
    fun `a revoked identity token can no longer mint a session`() {
        val verifier = BackendTestDoubles.FakeIdentityVerifier(revoked = setOf(BackendTestDoubles.idTokenFor("user-a")))
        val restricted = BackendTestHarness(
            gateway = gateway,
            identityVerifier = verifier
        )
        try {
            val response = restricted.post(
                SherifBackendServer.SESSION_PATH,
                """{"idToken":"${BackendTestDoubles.idTokenFor("user-a")}"}""",
                token = null
            )

            assertEquals(401, response.statusCode())
        } finally {
            restricted.stop()
        }
    }

    // --- Routing and shape ---------------------------------------------------

    @Test
    fun `an unknown path is refused`() {
        val token = harness.sessionFor("user-a")

        val response = harness.post("/v1/resume/delete-everything", BackendTestDoubles.aiRequestBody(), token)

        assertEquals(400, response.statusCode())
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `a get request to an ai endpoint is refused even with a valid session`() {
        val token = harness.sessionFor("user-a")

        val response = harness.get(AiOperation.RESUME_ANALYZE.path, token)

        assertEquals(400, response.statusCode())
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `a get request without a session is refused as unauthenticated`() {
        val response = harness.get(AiOperation.RESUME_ANALYZE.path)

        assertEquals(401, response.statusCode())
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `health does not require a session`() {
        val response = harness.get(SherifBackendServer.HEALTH_PATH)

        assertEquals(200, response.statusCode())
    }

    // --- Nothing sensitive escapes -------------------------------------------

    @Test
    fun `no error response discloses an upstream url or credential`() {
        val failing = BackendTestHarness(
            gateway = BackendTestDoubles.RecordingGeminiGateway(
                failure = BackendError.UPSTREAM_UNAVAILABLE
            )
        )
        try {
            val token = failing.sessionFor("user-a")

            val response = failing.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token)

            val body = response.body()
            assertEquals(502, response.statusCode())
            assertFalse("no internal url may leak", body.contains("googleapis.com"))
            assertFalse("no stack trace may leak", body.contains("at "))
            assertFalse("no upstream detail may leak", body.contains("caused by"))
        } finally {
            failing.stop()
        }
    }

    @Test
    fun `error responses are not cacheable`() {
        val response = harness.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token = null)

        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
    }

    @Test
    fun `an error response names the code but not the internal message`() {
        val response = harness.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token = null)

        val body = response.body()
        assertNotNull(BackendTestHarness.errorCode(response))
        assertFalse(
            "the client owns user-facing wording, not the server",
            body.contains(BackendError.UNAUTHENTICATED.clientMessage)
        )
    }

    @Test
    fun `responses forbid content sniffing`() {
        val token = harness.sessionFor("user-a")

        val response = harness.post(AiOperation.RESUME_ANALYZE.path, BackendTestDoubles.aiRequestBody(), token)

        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
    }

    @Test
    fun `every operation path is distinct`() {
        val paths = AiOperation.entries.map { it.path }

        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.all { it.startsWith("/v1/") })
    }
}
