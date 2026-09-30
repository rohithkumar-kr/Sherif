package com.sherif.backend.auth

import com.sherif.backend.BackendError
import com.sherif.backend.BackendException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The identity a verified request acts as. Always derived server-side. */
@Serializable
data class SessionPrincipal(
    val sub: String,
    val email: String? = null,
    val name: String? = null
)

/** The claims carried inside an access token. */
@Serializable
internal data class SessionClaims(
    val sub: String,
    val iat: Long,
    val exp: Long,
    val jti: String
)

/**
 * What the Android app stores and sends as `Authorization: Bearer <token>`.
 *
 * [expiresAt] is epoch **milliseconds**, not seconds. The `exp` claim inside
 * [SessionClaims] is seconds -- that is the JWT convention and `verify` depends
 * on it -- but this field exists for the client, and the client's session store
 * compares it against `System.currentTimeMillis()`. Issuing seconds here while
 * the client reads millis makes every session look expired the moment it is
 * created, so the token is never sent and the AI routes answer 401.
 */
@Serializable
data class IssuedSession(
    val accessToken: String,
    val userId: String,
    val expiresAt: Long
)

/** Milliseconds in a second. Converts the claim's seconds to the client's unit. */
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Issues and verifies SHERIF's own short-lived access tokens.
 *
 * The token is a compact `payload.hmac` pair. It is *not* a JWT: it is opaque
 * to the client, unsigned-but-verifiable only by this server, and its payload
 * is not a security boundary. The HMAC means a client cannot mint a token for
 * any `sub`, which is what stops a caller from simply claiming to be another
 * user.
 *
 * Identity originates from the Google ID token in [issue]; nothing in this
 * class ever accepts a `sub` from an AI request. That is the whole of the
 * authorization model for [com.sherif.backend.SherifBackendServer].
 *
 * Stateless on purpose: no session table to leak, and revocation is achieved by
 * a short lifetime plus the signing key.
 */
class SessionTokenCodec(
    private val signingKey: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() }
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    init {
        require(signingKey.size >= MIN_KEY_BYTES) {
            "Session signing key must be at least $MIN_KEY_BYTES bytes"
        }
    }

    /**
     * Mints an access token for an identity that has *already* been verified by
     * the identity provider.
     */
    fun issue(principal: SessionPrincipal, ttlSeconds: Long): IssuedSession {
        val nowSeconds = clock() / 1000
        val claims = SessionClaims(
            sub = principal.sub,
            iat = nowSeconds,
            exp = nowSeconds + ttlSeconds,
            jti = idGenerator()
        )
        val payload = urlEncoder.encodeToString(
            json.encodeToString(SessionClaims.serializer(), claims).toByteArray(StandardCharsets.UTF_8)
        )
        val signature = urlEncoder.encodeToString(sign(payload.toByteArray(StandardCharsets.UTF_8)))
        val token = "$payload.$signature"
        return IssuedSession(
            accessToken = token,
            userId = principal.sub,
            expiresAt = claims.exp * MILLIS_PER_SECOND
        )
    }

    /**
     * Verifies a bearer token and returns the identity it asserts.
     *
     * Distinguishes "you sent nothing" from "your session expired" so the
     * client can sign in again rather than showing a generic error.
     */
    fun verify(token: String?): SessionPrincipal {
        if (token.isNullOrBlank()) throw BackendException(BackendError.UNAUTHENTICATED)

        val separator = token.lastIndexOf('.')
        if (separator <= 0 || separator == token.length - 1) {
            throw BackendException(BackendError.UNAUTHENTICATED)
        }
        val payload = token.substring(0, separator)
        val providedSignature = token.substring(separator + 1)

        val expected = urlEncoder.encodeToString(sign(payload.toByteArray(StandardCharsets.UTF_8)))
        // Constant-time: a length or early-exit comparison would leak the
        // signature one byte at a time.
        val signatureMatches = MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            providedSignature.toByteArray(StandardCharsets.UTF_8)
        )
        if (!signatureMatches) throw BackendException(BackendError.UNAUTHENTICATED)

        val claims = decodeClaims(payload)
        val nowSeconds = clock() / 1000
        if (claims.exp <= nowSeconds) throw BackendException(BackendError.SESSION_EXPIRED)
        if (claims.sub.isBlank()) throw BackendException(BackendError.UNAUTHENTICATED)
        return SessionPrincipal(sub = claims.sub)
    }

    /**
     * Milliseconds a caller must wait before a token expiring at [expiresAt].
     *
     * [expiresAt] is in the same unit [IssuedSession] publishes: milliseconds.
     */
    fun millisUntilExpiry(expiresAt: Long): Long = expiresAt - clock()

    private fun decodeClaims(payload: String): SessionClaims = try {
        json.decodeFromString(
            SessionClaims.serializer(),
            String(urlDecoder.decode(payload), StandardCharsets.UTF_8)
        )
    } catch (e: Exception) {
        // A forged-but-correctly-shaped payload is an authentication failure,
        // not a server error, and must never surface its parse detail.
        throw BackendException(BackendError.UNAUTHENTICATED, cause = e)
    }

    private fun sign(payload: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(signingKey, HMAC_ALGORITHM))
        return mac.doFinal(payload)
    }

    companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"

        /** Refuses a guessable key. */
        const val MIN_KEY_BYTES = 32

        /** Long enough to survive a restart, short enough to bound exposure. */
        const val DEFAULT_TTL_SECONDS = 60L * 60L
    }
}
