package com.sherif.backend

import com.sherif.backend.ai.AiOperation
import com.sherif.backend.http.RateLimiter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RULE 14: one authenticated caller must not be able to spend the shared
 * Gemini key on their own.
 */
class RateLimitingTest {

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() = Unit

    @After
    fun tearDown() = Unit

    private fun limiter(
        perUser: Int = 3,
        perUserRefill: Int = 3,
        global: Int = 100,
        globalRefill: Int = 100
    ) = RateLimiter(
        perUserCapacity = perUser,
        perUserRefillPerMinute = perUserRefill,
        globalCapacity = global,
        globalRefillPerMinute = globalRefill,
        clock = { now }
    )

    private fun errorOf(block: () -> Unit): BackendError = try {
        block()
        throw AssertionError("expected the caller to be rate limited")
    } catch (e: BackendException) {
        e.error
    }

    @Test
    fun `a caller within budget is allowed`() {
        val subject = limiter()

        repeat(3) { subject.check("user-a") }
    }

    @Test
    fun `a caller over budget is rate limited`() {
        val subject = limiter()
        repeat(3) { subject.check("user-a") }

        assertEquals(BackendError.RATE_LIMITED, errorOf { subject.check("user-a") })
    }

    @Test
    fun `one user's spending does not affect another user`() {
        val subject = limiter()
        repeat(3) { subject.check("user-a") }

        subject.check("user-b")
    }

    @Test
    fun `budget refills over time`() {
        val subject = limiter(perUser = 2, perUserRefill = 2)
        repeat(2) { subject.check("user-a") }
        now += 60_000

        repeat(2) { subject.check("user-a") }
    }

    @Test
    fun `a global budget stops many users together`() {
        val subject = limiter(perUser = 100, perUserRefill = 100, global = 3, globalRefill = 3)
        subject.check("user-a")
        subject.check("user-b")
        subject.check("user-c")

        assertEquals(BackendError.RATE_LIMITED, errorOf { subject.check("user-d") })
    }

    @Test
    fun `the rate limited response carries a retry hint`() {
        val subject = limiter(perUser = 1, perUserRefill = 60)
        subject.check("user-a")

        val retryAfter = try {
            subject.check("user-a")
            null
        } catch (e: BackendException) {
            e.retryAfterSeconds
        }

        assertNotNull(retryAfter)
        assertTrue(retryAfter!! in 1..60)
    }

    // --- Over HTTP -----------------------------------------------------------

    @Test
    fun `a flooding client is refused with 429 and no gemini call`() {
        val gateway = BackendTestDoubles.RecordingGeminiGateway()
        val harness = BackendTestHarness(
            gateway = gateway,
            rateLimiter = limiter(perUser = 2, perUserRefill = 2)
        )
        try {
            val token = harness.sessionFor("user-a")
            val body = BackendTestDoubles.aiRequestBody()

            harness.post(AiOperation.RESUME_ANALYZE.path, body, token)
            harness.post(AiOperation.RESUME_ANALYZE.path, body, token)
            val blocked = harness.post(AiOperation.RESUME_ANALYZE.path, body, token)

            assertEquals(429, blocked.statusCode())
            assertEquals(BackendError.RATE_LIMITED.name, BackendTestHarness.errorCode(blocked))
            assertEquals("only the two allowed calls may reach gemini", 2, gateway.calls)
            assertTrue(
                "a retry hint must be sent",
                blocked.headers().firstValue("Retry-After").isPresent
            )
        } finally {
            harness.stop()
        }
    }
}
