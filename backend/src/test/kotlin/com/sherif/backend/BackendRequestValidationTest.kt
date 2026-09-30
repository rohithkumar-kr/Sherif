package com.sherif.backend

import com.sherif.backend.ai.AiOperation
import com.sherif.backend.ai.MAX_QUESTION_PROMPT
import com.sherif.backend.ai.MAX_RESUME_PROMPT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RULE 13 and AC-10: what the backend refuses, and -- just as importantly --
 * that a refusal costs no Gemini call.
 */
class BackendRequestValidationTest {

    private lateinit var gateway: BackendTestDoubles.RecordingGeminiGateway
    private lateinit var harness: BackendTestHarness
    private lateinit var token: String

    @Before
    fun setUp() {
        gateway = BackendTestDoubles.RecordingGeminiGateway()
        harness = BackendTestHarness(gateway = gateway)
        token = harness.sessionFor("user-a")
    }

    @After
    fun tearDown() = harness.stop()

    private fun post(body: String, path: String = AiOperation.RESUME_ANALYZE.path) =
        harness.post(path, body, token)

    private fun assertRejected(body: String, expected: BackendError, path: String = AiOperation.RESUME_ANALYZE.path) {
        val before = gateway.calls
        val response = post(body, path)

        assertEquals(expected.status, response.statusCode())
        assertEquals(expected.name, BackendTestHarness.errorCode(response))
        assertEquals("a rejected request must not spend an API call", before, gateway.calls)
    }

    @Test
    fun `a well formed request is accepted`() {
        assertEquals(200, post(BackendTestDoubles.aiRequestBody()).statusCode())
    }

    @Test
    fun `body that is not json is rejected`() {
        assertRejected("this is not json at all", BackendError.MALFORMED_REQUEST)
    }

    @Test
    fun `an empty body is rejected`() {
        assertRejected("", BackendError.MALFORMED_REQUEST)
    }

    @Test
    fun `a json array is rejected`() {
        assertRejected("[1,2,3]", BackendError.MALFORMED_REQUEST)
    }

    @Test
    fun `a body without contents is rejected`() {
        assertRejected("""{"generationConfig":{"responseMimeType":"application/json","responseSchema":{"type":"object"}}}""", BackendError.MALFORMED_REQUEST)
    }

    @Test
    fun `a body whose contents is not an array is rejected`() {
        assertRejected("""{"contents":"hello"}""", BackendError.MALFORMED_REQUEST)
    }

    @Test
    fun `a body with no parts is rejected`() {
        assertRejected("""{"contents":[{"role":"user"}]}""", BackendError.MALFORMED_REQUEST)
    }

    @Test
    fun `a blank prompt is rejected`() {
        assertRejected(BackendTestDoubles.aiRequestBody(prompt = "   "), BackendError.MALFORMED_REQUEST)
    }

    @Test
    fun `multiple contents are rejected rather than partially served`() {
        val body = """
            {"contents":[
              {"parts":[{"text":"first question about Kotlin"}]},
              {"parts":[{"text":"second unrelated request"}]}
            ],"generationConfig":{"responseMimeType":"application/json","responseSchema":{"type":"object"}}}
        """.trimIndent()

        assertRejected(body, BackendError.MALFORMED_REQUEST)
    }

    @Test
    fun `a request that does not ask for json output is rejected`() {
        assertRejected(
            BackendTestDoubles.aiRequestBody(mimeType = "text/plain"),
            BackendError.MALFORMED_REQUEST
        )
    }

    @Test
    fun `a request with no response mime type is rejected`() {
        assertRejected(
            BackendTestDoubles.aiRequestBody(mimeType = null),
            BackendError.MALFORMED_REQUEST
        )
    }

    @Test
    fun `a request with no response schema is rejected`() {
        assertRejected(
            BackendTestDoubles.aiRequestBody(includeSchema = false),
            BackendError.MALFORMED_REQUEST
        )
    }

    @Test
    fun `an oversized body is rejected`() {
        val huge = BackendTestDoubles.aiRequestBody(prompt = "A".repeat(MAX_RESUME_PROMPT + 1_000))

        assertRejected(huge, BackendError.PAYLOAD_TOO_LARGE)
    }

    @Test
    fun `a resume prompt just under the limit is accepted`() {
        val large = BackendTestDoubles.aiRequestBody(prompt = "A".repeat(MAX_RESUME_PROMPT - 1_000))

        assertEquals(200, post(large).statusCode())
    }

    @Test
    fun `a question prompt that is legal for resume analysis is too big for question generation`() {
        val prompt = "A".repeat(MAX_QUESTION_PROMPT + 1_000)

        assertRejected(
            BackendTestDoubles.aiRequestBody(prompt = prompt),
            BackendError.PAYLOAD_TOO_LARGE,
            path = AiOperation.INTERVIEW_QUESTION.path
        )
    }

    @Test
    fun `an oversized body reports the payload error rather than a gemini call`() {
        val response = post(BackendTestDoubles.aiRequestBody(prompt = "A".repeat(MAX_RESUME_PROMPT + 1_000)))

        assertEquals(413, response.statusCode())
        assertEquals(BackendError.PAYLOAD_TOO_LARGE.name, BackendTestHarness.errorCode(response))
    }

    @Test
    fun `the validated prompt is the one forwarded upstream`() {
        post(BackendTestDoubles.aiRequestBody(prompt = "Explain Kotlin coroutines to me."))

        assertEquals("Explain Kotlin coroutines to me.", gateway.received.single().prompt)
    }

    @Test
    fun `validation preserves the generation settings the client asked for`() {
        post(BackendTestDoubles.aiRequestBody())

        val forwarded = gateway.received.single()
        assertEquals(0.7, forwarded.temperature!!, 0.0001)
        assertEquals(1024, forwarded.maxOutputTokens)
        assertEquals("application/json", forwarded.responseMimeType)
    }
}
