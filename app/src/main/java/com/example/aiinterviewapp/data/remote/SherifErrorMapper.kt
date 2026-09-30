package com.example.aiinterviewapp.data.remote

import com.example.aiinterviewapp.data.remote.model.SherifBackendException
import com.example.aiinterviewapp.data.remote.model.SherifErrorCode
import com.example.aiinterviewapp.utils.Constants
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * Maps backend failures to user-facing messages.
 *
 * Two things changed from Phase 2 and both are deliberate.
 *
 * The app no longer holds an API key, so no message talks about keys any more.
 * Telling a user to "check that your API key is valid" was always misleading --
 * the user cannot fix a server credential -- and with the key moved to the
 * backend it would be actively wrong (RULE 13).
 *
 * 401 is now about the *user's* session, not the server's credential. That
 * distinction is load-bearing: an expired session must send the user back to
 * sign-in, so [SherifBackendException.requiresSignIn] is available and the
 * wording says so.
 */
object SherifErrorMapper {

    private val errorJson = Json { ignoreUnknownKeys = true }

    /**
     * The wire code to the client code.
     *
     * The backend serialises its own enum with `.name`, so it sends
     * `RATE_LIMITED`, while [SherifErrorCode] is annotated for `rate_limited`.
     * Decoding the body straight into `BackendErrorBody` therefore throws on
     * *every* error -- which is why the previous version could not use the code
     * at all and fell back to guessing from the status line. The status cannot
     * distinguish a rate limit from an upstream outage, and cannot express a
     * misconfigured server at all, so guessing threw away the one piece of
     * information the server volunteered.
     *
     * Both spellings are accepted, and an unrecognised code falls back to the
     * status. A server that adds a new code degrades to the status-based
     * message rather than failing to parse.
     */
    fun codeFor(rawCode: String?, statusCode: Int): SherifErrorCode {
        val normalised = rawCode?.trim()?.uppercase() ?: return codeForStatus(statusCode)

        val named = when (normalised) {
            "MALFORMED_REQUEST", "MALFORMEDREQUEST" -> SherifErrorCode.MALFORMED_REQUEST
            "UNAUTHENTICATED" -> SherifErrorCode.UNAUTHENTICATED
            "SESSION_EXPIRED", "SESSIONEXPIRED" -> SherifErrorCode.SESSION_EXPIRED
            "IDENTITY_TOKEN_INVALID", "IDENTITYTOKENINVALID" -> SherifErrorCode.IDENTITY_TOKEN_INVALID
            "IDENTITY_UNAVAILABLE", "IDENTITYUNAVAILABLE" -> SherifErrorCode.IDENTITY_UNAVAILABLE
            "FORBIDDEN" -> SherifErrorCode.FORBIDDEN
            "RATE_LIMITED", "RATELIMITED" -> SherifErrorCode.RATE_LIMITED
            "PAYLOAD_TOO_LARGE", "PAYLOADTOOLARGE" -> SherifErrorCode.PAYLOAD_TOO_LARGE
            "UPSTREAM_UNAVAILABLE", "UPSTREAMUNAVAILABLE" -> SherifErrorCode.UPSTREAM_UNAVAILABLE
            "UPSTREAM_REJECTED", "UPSTREAMREJECTED" -> SherifErrorCode.UPSTREAM_REJECTED
            "NOT_CONFIGURED", "NOTCONFIGURED" -> SherifErrorCode.NOT_CONFIGURED
            // The backend names this one `INTERNAL`; the client enum is
            // `INTERNAL_ERROR`. Both spellings are accepted so a change on
            // either side does not silently degrade the message.
            "INTERNAL_ERROR", "INTERNALERROR", "INTERNAL" -> SherifErrorCode.INTERNAL_ERROR
            "UNKNOWN" -> SherifErrorCode.UNKNOWN
            // snake_case, in case a caller ever serialises the client enum.
            else -> SherifErrorCode.entries.firstOrNull { it.name == normalised }
        }

        return named ?: codeForStatus(statusCode)
    }

    /**
     * The fallback when there is no usable code in the body.
     *
     * Ordered so the specific statuses win over the general 5xx range. 502 is
     * separated from 503 because the backend uses them for genuinely different
     * things: 502 when Gemini answered with a refusal, 503 when Gemini was not
     * reachable at all.
     */
    fun codeForStatus(statusCode: Int): SherifErrorCode = when (statusCode) {
        400 -> SherifErrorCode.MALFORMED_REQUEST
        401 -> SherifErrorCode.SESSION_EXPIRED
        403 -> SherifErrorCode.FORBIDDEN
        404 -> SherifErrorCode.MALFORMED_REQUEST
        409 -> SherifErrorCode.MALFORMED_REQUEST
        413 -> SherifErrorCode.PAYLOAD_TOO_LARGE
        429 -> SherifErrorCode.RATE_LIMITED
        502 -> SherifErrorCode.UPSTREAM_REJECTED
        503 -> SherifErrorCode.UPSTREAM_UNAVAILABLE
        504 -> SherifErrorCode.UPSTREAM_UNAVAILABLE
        in 500..599 -> SherifErrorCode.INTERNAL_ERROR
        else -> SherifErrorCode.UNKNOWN
    }

    fun map(throwable: Throwable): SherifBackendException = when (throwable) {
        is SherifBackendException -> throwable
        is HttpException -> {
            val code = codeFromBody(throwable) ?: codeForStatus(throwable.code())
            SherifBackendException(code, SherifBackendException.messageFor(code))
        }
        is SerializationException -> SherifBackendException(
            SherifErrorCode.INTERNAL_ERROR,
            SherifErrorExceptionMessages.unreadableResponse
        )
        is IOException -> SherifBackendException(
            SherifErrorCode.UPSTREAM_UNAVAILABLE,
            connectionFailureMessage(Constants.isApiConfigured)
        )
        else -> SherifBackendException(
            SherifErrorCode.INTERNAL_ERROR,
            // Never `throwable.message` for an unrecognised failure: it can be
            // an OkHttp or JDBC string, and those have a habit of containing
            // host names. The log has the real cause; the screen does not.
            SherifBackendException.messageFor(SherifErrorCode.INTERNAL_ERROR)
        )
    }

    /**
     * Pulls the code out of the error body, or null if there isn't a usable one.
     *
     * Reads the body as a string rather than letting Retrofit decode it, because
     * an error response that is truncated, HTML from a proxy, or empty is
     * ordinary in the field and must not become a parse failure on top of the
     * real failure.
     */
    private fun codeFromBody(exception: HttpException): SherifErrorCode? {
        val body = try {
            exception.response()?.errorBody()?.string()
        } catch (e: IOException) {
            null
        }

        if (body.isNullOrBlank()) return null

        return try {
            val element = errorJson.parseToJsonElement(body).jsonObject["error"]
            val raw = element?.let { runCatching { it.jsonObject["code"] }.getOrNull() }
                ?.jsonPrimitive?.content
            raw?.let { codeFor(it, exception.code()) }
        } catch (e: Exception) {
            // SerializationException, IllegalArgumentException from an
            // unexpected shape -- a malformed body is not a second error to
            // report, it is just no code at all.
            null
        }
    }

    /**
     * The text for a failure to reach the backend at all, which means two
     * different things depending on whether the app was ever told where the
     * backend is.
     *
     * "The AI service is temporarily unavailable" is the honest description of
     * an outage and a useless description of a build that still has the
     * `.invalid` placeholder compiled in, because retrying will never help.
     * The parameter is explicit rather than read from [Constants] so both
     * branches can be exercised by a test.
     */
    fun connectionFailureMessage(apiConfigured: Boolean): String =
        if (apiConfigured) {
            SherifBackendException.messageFor(SherifErrorCode.UPSTREAM_UNAVAILABLE)
        } else {
            "This build isn't connected to a SHERIF backend. " +
                "Set SHERIF_API_BASE_URL in local.properties."
        }

    /** The user-facing text for an arbitrary failure. */
    fun message(throwable: Throwable): String = map(throwable).message
}

/**
 * Wording that does not belong to a single error code.
 *
 * Kept apart from [SherifBackendException.messageFor] because these describe a
 * failure to *communicate* rather than a decision the backend reported.
 */
private object SherifErrorExceptionMessages {

    /**
     * A response that arrived but could not be read.
     *
     * This is a "something went wrong" and not a "try again" case: a truncated
     * body means the app and the server disagree about the contract, and
     * hammering it will not resolve that.
     */
    const val unreadableResponse = "The server's response could not be read."
}

/**
 * Replaces any failure with a [SherifBackendException] whose message is safe to
 * show, keeping the original as the cause.
 *
 * Shared by all three AI operations so they surface identical wording, and so
 * a session-expiry response is recognised the same way wherever it arrives.
 */
internal fun <T> Result<T>.withSherifErrorMapping(): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { Result.failure(SherifErrorMapper.map(it)) }
)
