package com.example.aiinterviewapp.data.remote.model

import kotlinx.serialization.Serializable

/**
 * The session exchange body.
 *
 * A Google ID token is a signed assertion of identity, not a secret SHERIF
 * hands out, so sending it here is the point: the backend checks Google's
 * signature and only then learns who the caller is.
 */
@Serializable
data class BackendSessionRequest(
    val idToken: String
)

/**
 * The issued session.
 *
 * [accessToken] is what every later call presents as `Authorization: Bearer`.
 * [userId] is displayed and used to scope local data; it is never sent to the
 * backend as proof of identity, because the backend derives it from the token.
 */
@Serializable
data class BackendSessionResponse(
    val accessToken: String,
    val userId: String,
    val expiresAt: Long
)
