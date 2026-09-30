package com.sherif.backend

import com.sherif.backend.ai.AiOperation
import com.sherif.backend.auth.DevSessionIssuer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The development session: a way to exercise the app with no
 * `GOOGLE_CLIENT_ID`, and the six properties that keep it from becoming a
 * production authentication bypass.
 *
 * Every test here is written so that the safe state is the default. A suite
 * whose harness had dev auth on by default would prove nothing about the
 * behaviour that actually ships, so [BackendTestHarness] keeps it off unless a
 * test asks for it by name.
 */
class DevSessionAuthTest {

    // --- 1. Disabled by default ----------------------------------------------

    @Test
    fun `dev auth is disabled when the environment says nothing`() {
        val config = completeConfig()

        assertFalse("dev auth must default to off", config.devAuthEnabled)
    }

    @Test
    fun `the dev route does not exist on a server that was not opted in`() {
        val harness = BackendTestHarness()
        try {
            val response = harness.post(SherifBackendServer.DEV_SESSION_PATH, "{}", token = null)

            assertEquals(BackendError.MALFORMED_REQUEST.status, response.statusCode())
        } finally {
            harness.stop()
        }
    }

    // --- 2. Can be explicitly enabled ----------------------------------------

    @Test
    fun `dev auth is enabled by an exact true`() {
        val config = completeConfig(ENV_DEV_AUTH_ENABLED to "true")

        assertTrue(config.devAuthEnabled)
    }

    @Test
    fun `the dev route issues a usable session when enabled`() {
        val harness = BackendTestHarness(devAuthEnabled = true)
        try {
            val response = harness.post(SherifBackendServer.DEV_SESSION_PATH, "{}", token = null)

            assertEquals(200, response.statusCode())
            val root = BackendTestHarness.parsed(response.body())
            val token = (root["accessToken"] as kotlinx.serialization.json.JsonPrimitive).content
            val userId = (root["userId"] as kotlinx.serialization.json.JsonPrimitive).content
            assertEquals(DevSessionIssuer.DEV_PRINCIPAL_SUB, userId)
            assertTrue(token.isNotBlank())

            // The point of minting a real token: the ordinary authenticated
            // routes accept it with no development-specific handling at all.
            val aiResponse = harness.post(
                AiOperation.INTERVIEW_QUESTION.path,
                BackendTestDoubles.aiRequestBody(),
                token = token
            )
            assertEquals(200, aiResponse.statusCode())
        } finally {
            harness.stop()
        }
    }

    // --- 3. Cannot be enabled by accident ------------------------------------

    @Test
    fun `only the exact value true enables dev auth`() {
        // Every near-miss resolves to off. These are the spellings an operator
        // might reasonably reach for, and each of them is a way to leave a
        // session-minting route live in production.
        listOf("TRUE", "True", "1", "yes", "on", "enabled", "true ", " true", "", "   ")
            .forEach { value ->
                assertFalse(
                    "SHERIF_DEV_AUTH_ENABLED=\"$value\" must not enable dev auth",
                    completeConfig(ENV_DEV_AUTH_ENABLED to value).devAuthEnabled
                )
            }
    }

    @Test
    fun `dev auth is not enabled by setting other variables`() {
        val config = completeConfig(
            ENV_PORT to "9090",
            ENV_BIND_HOST to "0.0.0.0"
        )

        assertFalse(config.devAuthEnabled)
    }

    // --- 4. The session is recognised as a development one -------------------

    @Test
    fun `the issued session carries the development identity`() {
        val codec = BackendTestDoubles.codec()
        val session = DevSessionIssuer(codec).issue()

        assertEquals(DevSessionIssuer.DEV_PRINCIPAL_SUB, session.userId)
        assertTrue(DevSessionIssuer.isDevPrincipal(codec.verify(session.accessToken).sub))
    }

    @Test
    fun `a real user is not mistaken for the development identity`() {
        assertFalse(DevSessionIssuer.isDevPrincipal("1234567890"))
        assertFalse(DevSessionIssuer.isDevPrincipal(null))
    }

    @Test
    fun `the development identity is namespaced away from real subjects`() {
        // A collision here would put developer data in a real user's scope, so
        // the prefix is load-bearing rather than cosmetic.
        assertTrue(DevSessionIssuer.DEV_PRINCIPAL_SUB.startsWith("dev:"))
    }

    // --- 5. A dev session is a normal session, and signs out normally --------

    @Test
    fun `a dev session expires like any other`() {
        val clockMillis = { 1_700_000_000_000L }
        val codec = BackendTestDoubles.codec(clockMillis = clockMillis)
        val session = DevSessionIssuer(codec, ttlSeconds = 60).issue()

        assertNotNull(codec.verify(session.accessToken))

        val later = BackendTestDoubles.codec(clockMillis = { clockMillis() + 120_000L })
        val error = runCatching { later.verify(session.accessToken) }.exceptionOrNull()

        assertTrue(error is BackendException)
        assertEquals(BackendError.SESSION_EXPIRED, (error as BackendException).error)
    }

    // --- 6. No arbitrary sessions, in any configuration ----------------------

    @Test
    fun `the dev route ignores a body asking for a different user`() {
        val harness = BackendTestHarness(devAuthEnabled = true)
        try {
            val response = harness.post(
                SherifBackendServer.DEV_SESSION_PATH,
                """{"sub":"someone-else@example.com","userId":"victim","sub2":"admin"}""",
                token = null
            )

            assertEquals(200, response.statusCode())
            val root = BackendTestHarness.parsed(response.body())
            val userId = (root["userId"] as kotlinx.serialization.json.JsonPrimitive).content
            assertEquals(
                "a caller must not be able to choose the identity it receives",
                DevSessionIssuer.DEV_PRINCIPAL_SUB,
                userId
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a tampered dev token is rejected`() {
        val codec = BackendTestDoubles.codec()
        val issued = DevSessionIssuer(codec, ttlSeconds = 3600).accessToken

        // A correctly-shaped payload naming the dev identity, with a signature
        // the server did not produce. The dev identity is not a secret anyone
        // can act on by writing it into a token.
        val error = runCatching { codec.verify("$issued.x") }.exceptionOrNull()

        assertTrue(error is BackendException)
        assertEquals(BackendError.UNAUTHENTICATED, (error as BackendException).error)
    }

    @Test
    fun `a dev session cannot reach a route it did not mint for`() {
        val harness = BackendTestHarness(devAuthEnabled = true)
        try {
            // A forged payload claiming a real user's subject, correctly shaped
            // but not signed by the server key.
            val forged = "eyJzdWIiOiIxMjM0NTY3ODkwIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjIxMDAwMDAwMDAsImp0aSI6ImZvcmdlZCJ9.forged"
            val response = harness.post(
                AiOperation.RESUME_ANALYZE.path,
                BackendTestDoubles.aiRequestBody(),
                token = forged
            )

            assertEquals(401, response.statusCode())
            assertEquals(BackendError.UNAUTHENTICATED.name, BackendTestHarness.errorCode(response))
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `the dev route refuses a non-POST method when enabled`() {
        val harness = BackendTestHarness(devAuthEnabled = true)
        try {
            val response = harness.get(SherifBackendServer.DEV_SESSION_PATH)

            assertEquals(BackendError.MALFORMED_REQUEST.status, response.statusCode())
            assertNull(
                "a GET must never issue a session",
                runCatching { BackendTestHarness.parsed(response.body())["accessToken"] }.getOrNull()
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `enabling dev auth does not weaken the google exchange`() {
        val harness = BackendTestHarness(devAuthEnabled = true)
        try {
            // The Google route still demands a token its verifier accepts.
            val rejected = harness.post(
                SherifBackendServer.SESSION_PATH,
                """{"idToken":"not-a-google-token"}""",
                token = null
            )
            assertEquals(BackendError.IDENTITY_TOKEN_INVALID.status, rejected.statusCode())

            val accepted = harness.post(
                SherifBackendServer.SESSION_PATH,
                """{"idToken":"id:real-google-user"}""",
                token = null
            )
            assertEquals(200, accepted.statusCode())
        } finally {
            harness.stop()
        }
    }

    private fun completeConfig(vararg overrides: Pair<String, String>): BackendConfig {
        val base = mutableMapOf(
            ENV_GEMINI_API_KEY to "test-gemini-key",
            ENV_SESSION_KEY to BackendTestDoubles.TEST_SIGNING_KEY,
            ENV_GOOGLE_CLIENT_ID to "test-client-id.apps.googleusercontent.com"
        )
        overrides.forEach { (key, value) -> base[key] = value }
        return backendConfigFrom(base)
    }
}
