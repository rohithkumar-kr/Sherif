package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry rules, which are the difference between a transient failure and a
 * request that cannot succeed no matter how many times it is sent.
 *
 * These are the cases the inline version got wrong. It retried every 429 on a
 * fixed 2s/4s/8s schedule and ignored the server's own advice, which meant the
 * client and the backend's per-user limiter spent three requests on each
 * rejection that the limiter had already decided the answer to.
 */
class RetryPolicyTest {

    @Test
    fun `server errors are the only status retried without advice`() {
        assertTrue(RetryPolicy.shouldRetry(500, attemptsMade = 0, retryAfterMillis = null))
        assertTrue(RetryPolicy.shouldRetry(502, attemptsMade = 0, retryAfterMillis = null))
        assertTrue(RetryPolicy.shouldRetry(503, attemptsMade = 0, retryAfterMillis = null))
    }

    @Test
    fun `client errors are never retried`() {
        listOf(400, 401, 403, 404, 409, 413, 422).forEach { status ->
            assertFalse(
                "status $status must not be retried",
                RetryPolicy.shouldRetry(status, attemptsMade = 0, retryAfterMillis = 1000L)
            )
        }
    }

    @Test
    fun `a rate limit is retried only when the server says when to return`() {
        assertTrue(RetryPolicy.shouldRetry(429, attemptsMade = 0, retryAfterMillis = 1_000L))
    }

    @Test
    fun `a rate limit with no advice is not retried`() {
        assertFalse(RetryPolicy.shouldRetry(429, attemptsMade = 0, retryAfterMillis = null))
    }

    @Test
    fun `a rate limit that asks for a long wait is surfaced instead of waited out`() {
        val sixtySeconds = 60_000L

        assertTrue(RetryPolicy.MAX_HONOURED_RETRY_AFTER_MILLIS < sixtySeconds)
        assertFalse(RetryPolicy.shouldRetry(429, attemptsMade = 0, retryAfterMillis = sixtySeconds))
    }

    @Test
    fun `attempts are bounded so one request cannot turn into an unbounded loop`() {
        var attempts = 0
        while (RetryPolicy.shouldRetry(503, attempts, retryAfterMillis = null)) {
            attempts++
        }

        assertEquals(RetryPolicy.MAX_ATTEMPTS, attempts)
    }

    @Test
    fun `server advice wins over the local backoff`() {
        assertEquals(750L, RetryPolicy.backoffMillis(nextAttempt = 1, retryAfterMillis = 750L))
    }

    @Test
    fun `the local backoff grows and is capped`() {
        assertEquals(RetryPolicy.BASE_BACKOFF_MILLIS, RetryPolicy.backoffMillis(1, null))
        assertEquals(RetryPolicy.BASE_BACKOFF_MILLIS * 2, RetryPolicy.backoffMillis(2, null))
        assertTrue(RetryPolicy.backoffMillis(3, null) <= RetryPolicy.MAX_BACKOFF_MILLIS)
        assertTrue(RetryPolicy.backoffMillis(50, null) <= RetryPolicy.MAX_BACKOFF_MILLIS)
    }

    @Test
    fun `a backoff never overflows into a negative wait`() {
        assertTrue(RetryPolicy.backoffMillis(Int.MAX_VALUE, null) >= 0L)
    }

    @Test
    fun `delta-seconds Retry-After is converted to milliseconds`() {
        assertEquals(2_000L, RetryPolicy.parseRetryAfterMillis("2", nowMillis = 0L))
    }

    @Test
    fun `an HTTP-date Retry-After is measured against the current time`() {
        val now = 1_700_000_000_000L
        val header = "Mon, 14 Nov 2023 22:13:40 GMT"

        assertEquals(20_000L, RetryPolicy.parseRetryAfterMillis(header, nowMillis = now))
    }

    @Test
    fun `an HTTP-date in the past means no wait rather than a negative one`() {
        val header = "Mon, 14 Nov 2023 22:13:20 GMT"

        assertEquals(0L, RetryPolicy.parseRetryAfterMillis(header, nowMillis = 1_700_000_000_000L))
    }

    @Test
    fun `an unreadable Retry-After yields no advice at all`() {
        listOf(null, "", "   ", "soon", "1.5", "-").forEach { header ->
            assertNull(
                "header '$header' must not be treated as a wait",
                RetryPolicy.parseRetryAfterMillis(header, nowMillis = 0L)
            )
        }
    }
}
