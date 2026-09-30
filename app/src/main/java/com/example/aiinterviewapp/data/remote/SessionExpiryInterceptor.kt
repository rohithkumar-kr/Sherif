package com.example.aiinterviewapp.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Notices the one status that means "this device's session is no longer valid".
 *
 * The alternative was to check `requiresSignIn` at every call site, and there
 * are three AI operations plus local reads that also fail when there is no
 * session. A miss at any of them is the dead-end this replaces. Deciding it once,
 * from the wire, means no screen can forget.
 *
 * Only 401 counts, and only for the AI endpoints. The session exchange also
 * answers 401, but there it means the *Google ID token* was rejected, which is a
 * sign-in failure the login screen already reports -- treating it as an expired
 * session would sign the user out of the very screen that is trying to sign them
 * in.
 *
 * This runs after the retry interceptor, so it only ever sees a final response:
 * a 401 is not retryable, but ordering it last makes that independent of the
 * retry policy.
 */
class SessionExpiryInterceptor @Inject constructor(
    private val notifier: SessionExpiryNotifier
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (response.code == HTTP_UNAUTHORIZED && !isSessionMinting(chain)) {
            notifier.report()
        }

        return response
    }

    /**
     * True for either route that mints a session.
     *
     * A 401 from the Google exchange means the *ID token* was rejected, which
     * the login screen already reports; treating it as an expired session would
     * sign the user out of the screen trying to sign them in. The development
     * exchange is included for the same reason, and also because a backend with
     * dev auth switched off does not have the route at all -- and a sign-out
     * triggered by a missing dev route would be a genuinely baffling bug.
     */
    private fun isSessionMinting(chain: Interceptor.Chain): Boolean =
        chain.request().url.encodedPath in SESSION_MINTING_PATHS

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val SESSION_PATH = "/v1/auth/session"
        const val DEV_SESSION_PATH = "/v1/auth/dev-session"

        val SESSION_MINTING_PATHS = setOf(SESSION_PATH, DEV_SESSION_PATH)
    }
}
