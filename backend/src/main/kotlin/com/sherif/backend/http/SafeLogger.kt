package com.sherif.backend.http

import com.sherif.backend.auth.fingerprintOf

/**
 * The backend's only logging surface.
 *
 * SHERIF's inputs are resumes, interview answers and credentials. None of them
 * may reach a log, so this type can only express safe fields: a method, a path,
 * a status, a duration and a non-reversible fingerprint of the user id. There
 * is deliberately no overload that accepts a request body, a header, a prompt
 * or a token, which is what makes "we never log resumes" a property of the code
 * rather than a promise in a review comment.
 */
interface SafeLogger {

    fun request(method: String, path: String, status: Int, durationMillis: Long, userId: String?)

    fun info(message: String)

    fun failure(message: String, throwable: Throwable?)
}

object NoopSafeLogger : SafeLogger {
    override fun request(method: String, path: String, status: Int, durationMillis: Long, userId: String?) = Unit
    override fun info(message: String) = Unit
    override fun failure(message: String, throwable: Throwable?) = Unit
}

/**
 * Writes one line per request to stderr.
 *
 * User ids are replaced by [com.sherif.backend.auth.logFingerprint] so that
 * correlating requests during an incident is possible without the log becoming
 * a list of Google account identifiers.
 */
class StderrSafeLogger(
    private val sink: (String) -> Unit = ::println
) : SafeLogger {

    override fun request(
        method: String,
        path: String,
        status: Int,
        durationMillis: Long,
        userId: String?
    ) {
        sink(
            "sherif request method=$method path=$path status=$status " +
                "durationMs=$durationMillis user=${userId?.let { fingerprintOf(it) } ?: "anonymous"}"
        )
    }

    override fun info(message: String) {
        sink("sherif info $message")
    }

    override fun failure(message: String, throwable: Throwable?) {
        // The exception type and message are safe: they are SHERIF's own
        // BackendException names. The cause chain is not logged, because that
        // is where upstream vendor text and URLs live.
        sink("sherif failure $message${throwable?.let { " type=${it::class.simpleName}" } ?: ""}")
    }
}
