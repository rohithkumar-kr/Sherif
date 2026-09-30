package com.example.aiinterviewapp.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Repeats a failed request according to [RetryPolicy].
 *
 * Extracted from the inline lambda it used to be, for one reason beyond tidiness:
 * the sleeping happens on the OkHttp dispatcher thread, and an inline
 * `Thread.sleep` buried in a client builder is impossible to test. The decision
 * is now pure and the interceptor is only the loop around it.
 *
 * The `Retry-After` header is read from the *first* failing response and re-checked
 * on each attempt, so a server that revises its advice upward is obeyed.
 *
 * Response bodies are closed before the next attempt. Skipping that leaks the
 * connection back to neither the pool nor the socket, which is how a retry loop
 * turns into a connection exhaustion bug under exactly the load that triggered
 * the retries.
 */
class RetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // A one-shot body -- a streaming upload, or a request that has already
        // been consumed -- cannot be replayed, so the request is not repeatable
        // at all. The backend's own endpoints take plain JSON, so this is a guard
        // rather than a path anyone hits today.
        if (request.body?.isOneShot() == true) {
            return chain.proceed(request)
        }

        var response = chain.proceed(request)
        var attemptsMade = 0

        while (true) {
            if (response.isSuccessful) return response

            val retryAfter = RetryPolicy.parseRetryAfterMillis(
                response.header(HEADER_RETRY_AFTER),
                System.currentTimeMillis()
            )

            if (!RetryPolicy.shouldRetry(response.code, attemptsMade, retryAfter)) {
                return response
            }

            attemptsMade++
            val waitMillis = RetryPolicy.backoffMillis(attemptsMade, retryAfter)

            // Close before waiting, not after: holding the connection open
            // through a multi-second sleep means the retry cannot start until the
            // timeout expires anyway.
            response.close()

            if (waitMillis > 0 && !sleep(waitMillis)) {
                throw IOException("Interrupted while backing off before a retry")
            }

            response = runCatching { chain.proceed(request) }
                .getOrElse { throwable ->
                    // The retry itself failed (a dropped connection, most
                    // likely). Surface the original response's status rather
                    // than the transport error, so the caller still learns
                    // something actionable.
                    throw IOException("Retry failed after $attemptsMade attempt(s)", throwable)
                }
        }
    }

    /**
     * Sleeps, returning false if the wait was cut short.
     *
     * A plain `Thread.sleep` swallowing [InterruptedException] would leave the
     * thread's interrupt flag set for whatever runs next, and a cancelled OkHttp
     * call during a backoff is an ordinary event, not an exceptional one.
     */
    private fun sleep(millis: Long): Boolean = try {
        TimeUnit.MILLISECONDS.sleep(millis)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private companion object {
        const val HEADER_RETRY_AFTER = "Retry-After"
    }
}
