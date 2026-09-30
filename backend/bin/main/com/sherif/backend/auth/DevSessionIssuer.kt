package com.sherif.backend.auth

/**
 * Mints a development session, and only ever for one fixed identity.
 *
 * This exists so the core app can be exercised on a machine with no
 * `GOOGLE_CLIENT_ID`, and it is deliberately built to be nearly useless for
 * anything else.
 *
 * ## The session it issues is a real one
 *
 * The token comes from the same [SessionTokenCodec] as a Google-authenticated
 * session, signed with the same key and verified by the same [verify] path. That
 * is the point: nothing downstream needs a special case. The AI routes, expiry
 * handling, rate limiting and the client's session store all behave exactly as
 * they do for a real user, so a development session is a faithful stand-in
 * rather than a parallel authentication system.
 *
 * ## Why it cannot be turned into a production backdoor
 *
 * - It is only reachable when the operator sets `SHERIF_DEV_AUTH_ENABLED=true`.
 *   Absent, blank, or any other value leaves the route unregistered, so the
 *   server answers `404` -- indistinguishable from a path that does not exist.
 * - The identity is the constant [DEV_PRINCIPAL]. A caller cannot ask for a
 *   user id: there is no request field, header or query parameter that reaches
 *   this class, so there is nothing to tamper with. The single [issue] signature
 *   takes no identity argument at all, which makes an arbitrary-`sub` call a
 *   compile error rather than a review question.
 * - [DEV_PRINCIPAL_SUB] is namespaced under `dev:`, which no Google subject can
 *   contain. Development data can therefore never collide with, or be
 *   mistaken for, a real user's data, and a development session is identifiable
 *   as one by anyone reading a log line.
 * - The signing key is unchanged. A dev token is as unforgeable as any other,
 *   so enabling this does not weaken the HMAC that stops a client minting a
 *   token for an arbitrary `sub`.
 *
 * The one thing this does change is that *one* known identity can obtain a
 * session without Google. That is acceptable precisely because it is opt-in,
 * off by default, and names a single person who is the developer running it.
 */
class DevSessionIssuer(
    private val sessionCodec: SessionTokenCodec,
    private val ttlSeconds: Long = SessionTokenCodec.DEFAULT_TTL_SECONDS
) {

    /**
     * Issues the development session.
     *
     * Takes no identity, no token and no request data. That is deliberate: the
     * only way to influence the result would be to add a parameter, and the
     * absence of one is the guarantee.
     */
    fun issue(): IssuedSession = sessionCodec.issue(DEV_PRINCIPAL, ttlSeconds)

    companion object {
        /**
         * The one identity a development session can ever act as.
         *
         * The `dev:` prefix is a reserved namespace, not decoration: Google
         * subjects are numeric, so this string cannot collide with a real one.
         *
         * Carries no email or name. [SessionTokenCodec] signs only `sub`, so
         * those fields would be a claim the token cannot honour, and an
         * apparently-invented identity is exactly what this class must not
         * produce.
         */
        val DEV_PRINCIPAL: SessionPrincipal = SessionPrincipal(sub = DEV_PRINCIPAL_SUB)

        const val DEV_PRINCIPAL_SUB = "dev:local"

        /** True when [sub] denotes the development identity. */
        fun isDevPrincipal(sub: String?): Boolean = sub == DEV_PRINCIPAL_SUB
    }
}
