package com.example.aiinterviewapp.data.local.datastore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The bearer token, mirrored in memory so a network call can read it
 * synchronously.
 *
 * OkHttp's [okhttp3.Interceptor.intercept] is a blocking contract, so a
 * suspending DataStore read inside it forces `runBlocking` on *every* request.
 * That was the previous design and it is the wrong one twice over: it burns an
 * OkHttp worker thread per in-flight request, and DataStore serialises reads
 * behind its own lock, so concurrent requests queue behind each other for no
 * reason.
 *
 * The fix keeps DataStore as the single source of truth and mirrors it here.
 * The mirror is fed by the same [SessionStore.accessToken] flow that already
 * drives the rest of the session, so there is exactly one write path -- sign-in
 * and sign-out do not have to remember to update a second copy, and cannot
 * drift from it. [SessionStore.accessToken] already filters out an expired
 * token, so a session that lapsed in the background reads as "no token" and the
 * request goes out unauthenticated rather than replaying a dead credential.
 *
 * A cold start has a brief window before the first DataStore emission arrives.
 * That is safe: the app gates every authenticated screen behind the splash
 * screen's session read, so no AI request can be issued during it. The value is
 * `@Volatile` because it is written on a coroutine dispatcher and read on an
 * OkHttp worker thread.
 *
 * Scoped in `di/NetworkModule` rather than here, because the process-lifetime
 * [CoroutineScope] and the source flow are both supplied there.
 */
class SessionTokenCache(
    scope: CoroutineScope,
    source: Flow<String?>
) {

    @Volatile
    private var cached: String? = null

    /**
     * The token to send right now, or null when signed out, expired, or the
     * store has not been read yet.
     *
     * Never blocks and never throws.
     */
    val current: String? get() = cached

    init {
        scope.launch {
            source.collect { token ->
                cached = token
            }
        }
    }
}
