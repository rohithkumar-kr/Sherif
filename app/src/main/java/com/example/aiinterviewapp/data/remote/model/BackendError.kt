package com.example.aiinterviewapp.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The backend's error contract, mirrored on the client.
 *
 * The server sends a `code` from a closed set and never an internal message, so
 * this enum can be exhaustive. `UNKNOWN` is the one case that must exist: a
 * server deployed with a newer build could answer with a code this app has
 * never heard of, and crashing on that would be a worse failure than degrading
 * to a generic error.
 */
@Serializable
enum class SherifErrorCode {
    @SerialName("malformed_request")
    MALFORMED_REQUEST,

    @SerialName("unauthenticated")
    UNAUTHENTICATED,

    @SerialName("session_expired")
    SESSION_EXPIRED,

    @SerialName("identity_token_invalid")
    IDENTITY_TOKEN_INVALID,

    /** Google's token endpoint was unreachable, so an ID token could not be checked. */
    @SerialName("identity_unavailable")
    IDENTITY_UNAVAILABLE,

    @SerialName("rate_limited")
    RATE_LIMITED,

    @SerialName("payload_too_large")
    PAYLOAD_TOO_LARGE,

    @SerialName("upstream_unavailable")
    UPSTREAM_UNAVAILABLE,

    /** The upstream AI service refused the request. Not the app's fault, not retryable. */
    @SerialName("upstream_rejected")
    UPSTREAM_REJECTED,

    /** The server itself is misconfigured, e.g. a missing upstream credential. */
    @SerialName("not_configured")
    NOT_CONFIGURED,

    /** A valid session acting on something it does not own. */
    @SerialName("forbidden")
    FORBIDDEN,

    @SerialName("internal_error")
    INTERNAL_ERROR,

    @SerialName("unknown")
    UNKNOWN;

    /**
     * True when re-authenticating is the correct response.
     *
     * `IDENTITY_TOKEN_INVALID` is included because it is what the session
     * exchange answers when Google rejects the ID token, and retrying the same
     * token cannot succeed. The login screen is what handles it; the app-level
     * expiry signal is driven by the wire status, not by this flag.
     */
    val requiresSignIn: Boolean
        get() = this == UNAUTHENTICATED ||
            this == SESSION_EXPIRED ||
            this == IDENTITY_TOKEN_INVALID
}

/** The error body the backend returns for every failure. */
@Serializable
data class BackendErrorBody(
    val error: SherifErrorBody
)

@Serializable
data class SherifErrorBody(
    val code: SherifErrorCode,
    val message: String? = null
)

/**
 * A failed backend call, carrying the code.
 *
 * The code is what drives behaviour -- an expired session sends the user back to
 * sign-in, a rate limit asks them to wait. The message is display-only.
 */
class SherifBackendException(
    val code: SherifErrorCode,
    override val message: String
) : Exception(message) {

    /** True when re-authenticating is the correct response. */
    val requiresSignIn: Boolean get() = code.requiresSignIn

    companion object {
        /**
         * The user-facing text for a code.
         *
         * Written here rather than trusting the server's message so the wording
         * is a product decision, and so a server-side change cannot inject
         * arbitrary text into the UI.
         *
         * None of these mention a key, a token, a host or a path: the person
         * reading them cannot act on any of that, and echoing it back teaches a
         * user nothing they did not already have.
         */
        fun messageFor(code: SherifErrorCode): String = when (code) {
            SherifErrorCode.MALFORMED_REQUEST -> "The request could not be understood."
            SherifErrorCode.UNAUTHENTICATED -> "Please sign in to continue."
            SherifErrorCode.SESSION_EXPIRED -> "Your session expired. Please sign in again."
            SherifErrorCode.IDENTITY_TOKEN_INVALID -> "Google sign-in could not be verified. Please try again."
            SherifErrorCode.IDENTITY_UNAVAILABLE -> "Sign-in is temporarily unavailable. Please try again."
            SherifErrorCode.FORBIDDEN -> "You don't have access to that. Please sign in again."
            SherifErrorCode.RATE_LIMITED -> "Too many requests. Please wait a moment and try again."
            SherifErrorCode.PAYLOAD_TOO_LARGE -> "That input is too large to analyse."
            SherifErrorCode.UPSTREAM_UNAVAILABLE -> "The AI service is temporarily unavailable."
            SherifErrorCode.UPSTREAM_REJECTED -> "The AI service couldn't complete that request. Please try again."
            SherifErrorCode.NOT_CONFIGURED -> "The AI service isn't fully set up yet. Please try again later."
            SherifErrorCode.INTERNAL_ERROR -> "Something went wrong. Please try again."
            SherifErrorCode.UNKNOWN -> "Something went wrong. Please try again."
        }
    }
}
