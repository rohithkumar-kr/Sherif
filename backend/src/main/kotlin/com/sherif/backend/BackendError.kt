package com.sherif.backend

import kotlinx.serialization.Serializable

/**
 * The closed set of failure codes the API can return.
 *
 * Every rejection the backend emits is one of these, with a fixed status. That
 * is deliberate: a free-form error body is how internal URLs, upstream vendor
 * text and stack fragments leak to clients, so the response shape is
 * enumerated here and the message is chosen by the client, not sent from here.
 *
 * [status] is the only thing the wire carries. [clientMessage] exists purely
 * so the backend tests can assert the contract stays aligned with the Android
 * wording in `BackendErrorMapper`; the backend never sends it.
 */
enum class BackendError(val status: Int, val clientMessage: String) {

    /** No `Authorization` header, or one that is not a bearer token. */
    UNAUTHENTICATED(401, "Authentication required."),

    /** A well-formed token that has expired. */
    SESSION_EXPIRED(401, "Your session has expired. Please sign in again."),

    /** A valid session acting on something it does not own. */
    FORBIDDEN(403, "You are not authorized to perform this action."),

    /** Unparseable JSON, wrong shape, missing required fields. */
    MALFORMED_REQUEST(400, "The request could not be processed."),

    /** Body or prompt larger than the operation allows. */
    PAYLOAD_TOO_LARGE(413, "That file is too large to process."),

    /** Per-user or global budget exhausted. */
    RATE_LIMITED(429, "You're going a bit fast. Wait a moment and try again."),

    /** The supplied Google ID token failed verification. */
    IDENTITY_TOKEN_INVALID(401, "Authentication failed. Please sign in again."),

    /** Identity provider unreachable or misconfigured. */
    IDENTITY_UNAVAILABLE(503, "Sign-in is temporarily unavailable. Please try again."),

    /** Gemini rejected or failed the request. */
    UPSTREAM_REJECTED(502, "The AI service rejected the request. Please try again."),

    /** Gemini unreachable or timed out. */
    UPSTREAM_UNAVAILABLE(502, "The AI service is temporarily unavailable. Please try again."),

    /** Server misconfiguration, e.g. a missing Gemini key. */
    NOT_CONFIGURED(500, "The service is not correctly configured."),

    /** Anything unexpected. */
    INTERNAL(500, "Something went wrong. Please try again.");

    /** True when the client should drop its session and return to signed-out. */
    val invalidatesSession: Boolean
        get() = this == UNAUTHENTICATED || this == SESSION_EXPIRED || this == IDENTITY_TOKEN_INVALID
}

/** The single error envelope every non-2xx response uses. */
@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class ErrorBody(
    val code: String,
    val retryAfterSeconds: Int? = null
)

/**
 * Thrown internally to abort request handling with a specific [BackendError].
 *
 * Carries no upstream detail: the raw cause is kept for the server log only and
 * is never serialised into a response.
 */
class BackendException(
    val error: BackendError,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null
) : RuntimeException(error.name, cause)
