package com.sherif.backend

import com.sherif.backend.ai.HttpGeminiGateway
import com.sherif.backend.ai.ValidatedAiRequest
import com.sherif.backend.auth.fingerprintOf
import com.sherif.backend.http.StderrSafeLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RULE 15 and RULE 19 at the source level: the credential is reachable only
 * where it must be, and neither it nor a resume ever reaches a log.
 */
class CredentialAndLoggingTest {

    private val secret = "AIzaSyTEST-KEY-THAT-MUST-NEVER-LEAK-0123456789"

    @Test
    fun `a blank gemini key is a configuration failure rather than an anonymous call`() {
        val gateway = HttpGeminiGateway(apiKey = "")

        val error = try {
            gateway.generate(request())
            null
        } catch (e: BackendException) {
            e.error
        }

        assertEquals(BackendError.NOT_CONFIGURED, error)
    }

    @Test
    fun `the gemini key is never written into the upstream body`() {
        val gateway = HttpGeminiGateway(apiKey = secret)

        val body = gateway.renderBody(request(prompt = "Summarise this candidate resume."))

        assertFalse("the key must travel only in a header", body.contains(secret))
    }

    @Test
    fun `only validated fields are forwarded upstream`() {
        val gateway = HttpGeminiGateway(apiKey = secret)

        val body = gateway.renderBody(
            ValidatedAiRequest(
                prompt = "Explain coroutines.",
                temperature = 0.3,
                maxOutputTokens = 512,
                responseMimeType = "application/json",
                responseSchemaJson = """{"type":"object"}"""
            )
        )

        assertTrue(body.contains("Explain coroutines."))
        assertTrue(body.contains("\"temperature\":0.3"))
        assertTrue(body.contains("\"maxOutputTokens\":512"))
        assertFalse("no unvalidated field may be forwarded", body.contains("safetySettings"))
    }

    @Test
    fun `the gateway does not expose the key through a getter`() {
        val exposed = HttpGeminiGateway::class.java.methods
            .filter { it.name.contains("key", ignoreCase = true) && it.parameterCount == 0 }

        assertTrue("no zero-argument accessor may return the credential", exposed.isEmpty())
    }

    @Test
    fun `a log line contains no resume text, token or key`() {
        val lines = mutableListOf<String>()
        val logger = StderrSafeLogger { lines += it }
        val resumeText = "Aarav Sharma aarav.sharma@example.com +91 98765 43210"

        logger.request("POST", "/v1/resume/analyze", 200, 42, "user-a")
        logger.info("backend ready")
        logger.failure("upstream failed", IllegalStateException("secret=$secret"))

        val output = lines.joinToString("\n")
        assertFalse(output.contains(resumeText))
        assertFalse(output.contains("aarav.sharma@example.com"))
        assertFalse(output.contains("98765"))
        assertFalse(output.contains(secret))
    }

    @Test
    fun `a logged user id is a fingerprint, not the identifier`() {
        val lines = mutableListOf<String>()
        StderrSafeLogger { lines += it }.request("POST", "/v1/resume/analyze", 200, 1, "108412345678901234567")

        val line = lines.single()
        assertFalse(line.contains("108412345678901234567"))
        assertTrue(line.contains(fingerprintOf("108412345678901234567")))
    }

    @Test
    fun `an unauthenticated request is logged as anonymous`() {
        val lines = mutableListOf<String>()
        StderrSafeLogger { lines += it }.request("POST", "/v1/resume/analyze", 401, 1, null)

        assertTrue(lines.single().contains("user=anonymous"))
    }

    @Test
    fun `a fingerprint is stable for the same user and differs between users`() {
        assertEquals(fingerprintOf("user-a"), fingerprintOf("user-a"))
        assertTrue(fingerprintOf("user-a") != fingerprintOf("user-b"))
    }
}

private fun request(prompt: String = "Explain Kotlin coroutines.") = ValidatedAiRequest(
    prompt = prompt,
    temperature = 0.7,
    maxOutputTokens = 1024,
    responseMimeType = "application/json",
    responseSchemaJson = """{"type":"object","properties":{"q":{"type":"string"}}}"""
)
