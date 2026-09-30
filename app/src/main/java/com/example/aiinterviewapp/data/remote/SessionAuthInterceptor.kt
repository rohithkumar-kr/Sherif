package com.example.aiinterviewapp.data.remote

import com.example.aiinterviewapp.data.local.datastore.SessionTokenCache
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches the session token to SHERIF requests.
 *
 * The token comes from [SessionTokenCache], an in-memory mirror of
 * [com.example.aiinterviewapp.data.local.datastore.SessionStore]. This
 * interceptor previously read DataStore directly, which meant a `runBlocking`
 * call on every request: OkHttp's `intercept` is synchronous, so the only way to
 * await a suspending read was to block an OkHttp worker thread, and DataStore
 * serialises its own reads, so the calls also queued behind each other.
 *
 * The mirror is fed by the same store, so a token that expires while the app is
 * backgrounded still stops being presented: `SessionStore.accessToken` filters
 * on the stored expiry, the mirror goes null, the request goes out
 * unauthenticated, and [SessionExpiryInterceptor] reports the resulting 401.
 * The outcome is identical and nothing blocks.
 *
 * No request is ever logged with its token: the token appears only in the
 * `Authorization` header, and the logging interceptor redacts that header in
 * every build.
 */
class SessionAuthInterceptor @Inject constructor(
    private val tokenCache: SessionTokenCache
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // The session exchange is the one call that must not carry a token: it
        // is what mints one. The development exchange mints one too, so it is
        // excluded for the same reason.
        //
        // Matching is by exact path rather than by suffix. A suffix test would
        // mean that anything ending in `/v1/auth/session` quietly became
        // unauthenticated too, which is the shape of mistake that removes the
        // `Authorization` header from a route that needs it.
        if (request.url.encodedPath in UNAUTHENTICATED_PATHS) {
            return chain.proceed(request)
        }

        val token = tokenCache.current ?: return chain.proceed(request)

        val authorized = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authorized)
    }

    private companion object {
        const val SESSION_PATH = "/v1/auth/session"
        const val DEV_SESSION_PATH = "/v1/auth/dev-session"

        /** Routes that are unauthenticated by design: both mint a session. */
        val UNAUTHENTICATED_PATHS = setOf(SESSION_PATH, DEV_SESSION_PATH)
    }
}
