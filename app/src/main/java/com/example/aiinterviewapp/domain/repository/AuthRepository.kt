package com.example.aiinterviewapp.domain.repository

import android.app.Activity

/**
 * Signing in and out.
 *
 * The domain deliberately does not mention Google: an implementation may
 * federate any identity provider. What the rest of the app depends on is only
 * that a successful sign-in yields a *server-verified* user id.
 */
interface AuthRepository {

    /**
     * Runs the identity flow and, on success, stores a SHERIF session.
     *
     * Returns the user id the backend assigned. Callers must scope local data by
     * that value rather than by anything the device previously knew.
     */
    suspend fun signInWithGoogle(activity: Activity): AuthResult

    /**
     * Signs in as the single development identity, where a debug build is
     * talking to a backend started with `SHERIF_DEV_AUTH_ENABLED=true`.
     *
     * Returns the same [AuthResult] as [signInWithGoogle] and produces the same
     * stored session, so callers cannot tell the two apart and do not need to.
     * Implementations must refuse in a build where development sign-in is
     * disabled, and [signOut] must clear a session obtained this way exactly as
     * it clears any other.
     *
     * This is a testing affordance, not an authentication method: there is no
     * real identity behind it and it must never be reachable in production.
     */
    suspend fun signInForDevelopment(): AuthResult

    /** Ends the session locally, leaving user-owned content in place. */
    suspend fun signOut()
}

/** Outcome of a sign-in attempt. */
sealed interface AuthResult {
    data class Success(val userId: String) : AuthResult
    data class Failure(val message: String) : AuthResult
}
