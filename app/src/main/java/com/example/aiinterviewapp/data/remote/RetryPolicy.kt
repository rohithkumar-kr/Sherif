package com.example.aiinterviewapp.data.remote

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Decides whether a failed request is worth sending again, and how long to wait.
 *
 * This exists as a pure object rather than as interceptor code because the
 * previous inline version was wrong in a way only a test would have caught: it
 * retried 429 with a fixed 2s/4s/8s backoff and ignored the `Retry-After` the
 * backend sends. The backend's per-user budget refills at 20/minute, so a retry
 * at 2s was very likely to be refused again -- the client retry loop and the
 * server limiter spending requests on each other, three times per operation.
 *
 * The rules:
 *
 *  * **429 is not retried unless the server says when to come back, and only if
 *    that moment is imminent.** A `Retry-After` beyond [MAX_HONOURED_RETRY_AFTER_MILLIS]
 *    is surfaced to the user as "wait a moment" instead of being waited out on
 *    a screen that looks frozen.
 *  * **429 without `Retry-After` is not retried.** A blind retry against a
 *    limiter is how a retry storm starts.
 *  * **5xx is retried** with a short capped backoff: the backend answers 5xx
 *    when the upstream AI call failed, so a second attempt is genuinely likely
 *    to behave differently.
 *  * **Everything else in 4xx is terminal.** 400, 401, 403, 404, 409 and 413 all
 *    describe a request that will fail identically however many times it is
 *    sent, and retrying an authentication or configuration failure is the most
 *    expensive way to learn nothing.
 *
 * Nothing here is layered on top of another retry. The backend does not retry
 * Gemini at all -- [com.sherif.backend.ai.HttpGeminiGateway] sends exactly one
 * request -- so this is the only layer that repeats anything, and [MAX_ATTEMPTS]
 * bounds it.
 */
object RetryPolicy {

    /** One original request plus at most two repeats. */
    const val MAX_ATTEMPTS = 3

    const val BASE_BACKOFF_MILLIS = 500L
    const val MAX_BACKOFF_MILLIS = 4_000L

    /**
     * The longest `Retry-After` this client is willing to sit out.
     *
     * The backend's limiter caps its own advice at 60 seconds. Waiting that long
     * behind a spinner is worse than telling the user to try again, so anything
     * beyond a few seconds is declined rather than honoured.
     */
    const val MAX_HONOURED_RETRY_AFTER_MILLIS = 5_000L

    /** True for statuses where a repeat could plausibly produce a different answer. */
    fun isTransient(code: Int): Boolean = code in 500..599

    /**
     * How long to wait before attempt number [nextAttempt] (1-based: the first
     * repeat is attempt 1).
     *
     * A server-supplied wait always wins over the local backoff, because the
     * server is the only party that knows when its budget refills.
     */
    fun backoffMillis(nextAttempt: Int, retryAfterMillis: Long?): Long {
        if (retryAfterMillis != null) return retryAfterMillis.coerceAtLeast(0L)

        val exponent = (nextAttempt - 1).coerceIn(0, 8)
        val exponential = BASE_BACKOFF_MILLIS shl exponent
        return exponential.coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /**
     * Whether a request that already used [attemptsMade] attempts should be sent
     * again after receiving [code].
     */
    fun shouldRetry(
        code: Int,
        attemptsMade: Int,
        retryAfterMillis: Long?
    ): Boolean {
        if (attemptsMade >= MAX_ATTEMPTS) return false
        if (isTransient(code)) return true
        if (code != HTTP_TOO_MANY_REQUESTS) return false

        // A rate limit is only worth waiting out when the server has said the
        // wait is short. Otherwise the answer is to tell the user, not to hold
        // the request open and pile more load onto the limiter.
        return retryAfterMillis != null &&
            retryAfterMillis <= MAX_HONOURED_RETRY_AFTER_MILLIS
    }

    /**
     * Parses a `Retry-After` header into a wait in milliseconds.
     *
     * The header has two forms, delta-seconds and an HTTP date, and both are in
     * use in the wild. Anything unparseable yields null, which makes the caller
     * treat it as "no advice given" rather than "wait zero seconds".
     */
    fun parseRetryAfterMillis(value: String?, nowMillis: Long): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null

        raw.toLongOrNull()?.let { seconds -> return (seconds * 1000L).coerceAtLeast(0L) }

        return try {
            val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            (target.toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0L)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    const val HTTP_TOO_MANY_REQUESTS = 429
}
