package com.sherif.backend

import com.sherif.backend.ai.AiOperation
import com.sherif.backend.auth.fingerprintOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RULE 21 and RULE 12, at the backend boundary.
 *
 * The scenario is the deterministic two-user one: A uploads a resume and runs
 * an interview, B does the same, and neither may act as the other. Since the
 * backend holds no user data of its own, the property under test is the one it
 * actually owns: **the identity a request is attributed to comes from the token
 * and nothing else.**
 */
class BackendUserIsolationTest {

    private lateinit var gateway: BackendTestDoubles.RecordingGeminiGateway
    private lateinit var logger: RecordingSafeLogger
    private lateinit var harness: BackendTestHarness

    private lateinit var userAToken: String
    private lateinit var userBToken: String

    @Before
    fun setUp() {
        gateway = BackendTestDoubles.RecordingGeminiGateway()
        logger = RecordingSafeLogger()
        harness = BackendTestHarness(gateway = gateway, logger = logger)
        userAToken = harness.sessionFor(USER_A)
        userBToken = harness.sessionFor(USER_B)
    }

    @After
    fun tearDown() = harness.stop()

    // --- Deterministic two-user scenario -------------------------------------

    @Test
    fun `user a's request is attributed to user a`() {
        harness.post(AiOperation.RESUME_ANALYZE.path, userAResumeBody(), userAToken)

        assertTrue(logger.usersWhoCalled().contains(USER_A))
        assertFalse(logger.usersWhoCalled().contains(USER_B))
    }

    @Test
    fun `user b's request is attributed to user b`() {
        harness.post(AiOperation.INTERVIEW_QUESTION.path, userBQuestionBody(), userBToken)

        assertTrue(logger.usersWhoCalled().contains(USER_B))
        assertFalse(logger.usersWhoCalled().contains(USER_A))
    }

    @Test
    fun `both users are served and each call is attributed separately`() {
        harness.post(AiOperation.RESUME_ANALYZE.path, userAResumeBody(), userAToken)
        harness.post(AiOperation.RESUME_ANALYZE.path, userBResumeBody(), userBToken)
        harness.post(AiOperation.INTERVIEW_QUESTION.path, userBQuestionBody(), userBToken)

        assertEquals(3, gateway.calls)
        val subjects = logger.entries.filter { it.path != SherifBackendServer.SESSION_PATH }.map { it.userId }
        assertEquals(listOf(USER_A, USER_B, USER_B), subjects)
    }

    @Test
    fun `each user's own text is what reaches the model`() {
        harness.post(AiOperation.RESUME_ANALYZE.path, userAResumeBody(), userAToken)
        harness.post(AiOperation.RESUME_ANALYZE.path, userBResumeBody(), userBToken)

        val prompts = gateway.received.map { it.prompt }
        assertTrue(prompts[0].contains(USER_A_TOKEN))
        assertFalse(prompts[0].contains(USER_B_TOKEN))
        assertTrue(prompts[1].contains(USER_B_TOKEN))
        assertFalse(prompts[1].contains(USER_A_TOKEN))
    }

    // --- A client-supplied identity is inert ---------------------------------

    @Test
    fun `a body claiming another user does not change the authenticated identity`() {
        // User B's token, with User A's id asserted in the body.
        val response = harness.post(
            AiOperation.RESUME_ANALYZE.path,
            BackendTestDoubles.aiRequestBodyWithUserIdClaim(USER_A, prompt = userAResumeBodyPrompt()),
            userBToken
        )

        assertEquals(200, response.statusCode())
        val aiEntries = logger.entries.filter { it.path == AiOperation.RESUME_ANALYZE.path }
        assertEquals("the token, not the body, decides the caller", USER_B, aiEntries.single().userId)
    }

    @Test
    fun `a forged token is refused even when the body names a real user`() {
        val forged = userBToken.dropLast(2) + "XY"

        val response = harness.post(
            AiOperation.RESUME_ANALYZE.path,
            BackendTestDoubles.aiRequestBodyWithUserIdClaim(USER_A),
            forged
        )

        assertEquals(401, response.statusCode())
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `a user cannot act with an empty token`() {
        val response = harness.post(
            AiOperation.INTERVIEW_EVALUATE.path,
            BackendTestDoubles.aiRequestBodyWithUserIdClaim(USER_A),
            token = ""
        )

        assertEquals(401, response.statusCode())
        assertEquals(0, gateway.calls)
    }

    // --- Tokens are not interchangeable --------------------------------------

    @Test
    fun `user a's token and user b's token are different credentials`() {
        assertNotEquals(userAToken, userBToken)
    }

    @Test
    fun `two users receive distinct log fingerprints`() {
        assertNotEquals(fingerprintOf(USER_A), fingerprintOf(USER_B))
    }

    @Test
    fun `a log fingerprint does not disclose the user id`() {
        val fingerprint = fingerprintOf(USER_A)

        assertFalse(fingerprint.contains(USER_A))
        assertEquals(8, fingerprint.length)
    }

    private fun userAResumeBody() = BackendTestDoubles.aiRequestBody(prompt = userAResumeBodyPrompt())

    private fun userBResumeBody() = BackendTestDoubles.aiRequestBody(prompt = userBResumeBodyPrompt())

    private fun userBQuestionBody() = BackendTestDoubles.aiRequestBody(
        prompt = "Candidate resume contains $USER_B_TOKEN. Ask one question."
    )

    private fun userAResumeBodyPrompt() =
        "Candidate resume contains $USER_A_TOKEN. Build a resume profile."

    private fun userBResumeBodyPrompt() =
        "Candidate resume contains $USER_B_TOKEN. Build a resume profile."

    companion object {
        const val USER_A = "108412345678901234567"
        const val USER_B = "108498765432109876543"
        const val USER_A_TOKEN = "ANDROID_USER_A_TOKEN"
        const val USER_B_TOKEN = "DATA_USER_B_TOKEN"
    }
}
