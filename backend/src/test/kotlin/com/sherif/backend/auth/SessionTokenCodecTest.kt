package com.sherif.backend.auth

import com.sherif.backend.BackendError
import com.sherif.backend.BackendException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The access token is the only thing standing between "I know a user id" and
 * "I am that user". These tests are about it refusing to be anything else.
 */
class SessionTokenCodecTest {

    private var nowMillis = 1_700_000_000_000L
    private val signingKey = "sherif-test-signing-key-0123456789abcdef".toByteArray(Charsets.UTF_8)
    private val codec = SessionTokenCodec(signingKey, clock = { nowMillis })

    private fun errorOf(block: () -> Unit): BackendError = try {
        block()
        throw AssertionError("expected a BackendException")
    } catch (e: BackendException) {
        e.error
    }

    @Test
    fun `a freshly issued token verifies to the same subject`() {
        val session = codec.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 3600)

        val principal = codec.verify(session.accessToken)

        assertEquals("user-a", principal.sub)
    }

    @Test
    fun `issuing exposes the user id and the expiry to the client`() {
        val session = codec.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 3600)

        assertEquals("user-a", session.userId)
        // Milliseconds, not seconds. The client compares this against
        // System.currentTimeMillis(), so issuing seconds would make a session
        // that was just created read as already expired.
        assertEquals(nowMillis + 3600 * 1000L, session.expiresAt)
    }

    @Test
    fun `a missing token is unauthenticated`() {
        assertEquals(BackendError.UNAUTHENTICATED, errorOf { codec.verify(null) })
    }

    @Test
    fun `a blank token is unauthenticated`() {
        assertEquals(BackendError.UNAUTHENTICATED, errorOf { codec.verify("   ") })
    }

    @Test
    fun `a token with no signature is unauthenticated`() {
        val session = codec.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 3600)
        val payload = session.accessToken.substringBefore('.')

        assertEquals(BackendError.UNAUTHENTICATED, errorOf { codec.verify(payload) })
    }

    @Test
    fun `a tampered signature is unauthenticated`() {
        val session = codec.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 3600)
        val forged = session.accessToken.dropLast(1) + "A"

        assertEquals(BackendError.UNAUTHENTICATED, errorOf { codec.verify(forged) })
    }

    /**
     * The core of RULE 12: a caller cannot edit the payload to become someone
     * else, because the payload is covered by the signature.
     */
    @Test
    fun `a payload edited to claim another user is rejected`() {
        val attacker = codec.issue(SessionPrincipal(sub = "attacker"), ttlSeconds = 3600)

        val forgedPayload = editPayloadSubject(
            attacker.accessToken,
            from = "attacker",
            to = "user-b"
        )

        assertEquals(
            BackendError.UNAUTHENTICATED,
            errorOf { codec.verify(forgedPayload) }
        )
    }

    @Test
    fun `a rewritten payload re-signed with the victim's own signature is rejected`() {
        val victim = codec.issue(SessionPrincipal(sub = "user-b"), ttlSeconds = 3600)
        val attacker = codec.issue(SessionPrincipal(sub = "attacker"), ttlSeconds = 3600)

        val forged = editPayloadSubject(attacker.accessToken, from = "attacker", to = "user-b") +
            "." + victim.accessToken.substringAfter('.')

        assertEquals(BackendError.UNAUTHENTICATED, errorOf { codec.verify(forged) })
    }

    @Test
    fun `a token issued for a blank subject authenticates nobody`() {
        val session = codec.issue(SessionPrincipal(sub = "   "), ttlSeconds = 3600)

        assertEquals(BackendError.UNAUTHENTICATED, errorOf { codec.verify(session.accessToken) })
    }

    /** Rewrites the `sub` claim inside a token, keeping the original signature. */
    private fun editPayloadSubject(token: String, from: String, to: String): String {
        val payload = token.substringBefore('.')
        val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        val edited = json.replace("\"$from\"", "\"$to\"")
        val reencoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(edited.toByteArray(Charsets.UTF_8))
        return "$reencoded.${token.substringAfter('.')}"
    }

    @Test
    fun `a token signed by a different key is rejected`() {
        val other = SessionTokenCodec("a-completely-different-32-byte-key!".toByteArray())
        val foreign = other.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 3600)

        assertEquals(BackendError.UNAUTHENTICATED, errorOf { codec.verify(foreign.accessToken) })
    }

    @Test
    fun `an expired token reports expiry rather than a generic failure`() {
        val session = codec.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 60)
        nowMillis += 61_000

        assertEquals(BackendError.SESSION_EXPIRED, errorOf { codec.verify(session.accessToken) })
    }

    @Test
    fun `a token is still valid one second before it expires`() {
        val session = codec.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 60)
        nowMillis += 59_000

        assertEquals("user-a", codec.verify(session.accessToken).sub)
    }

    @Test
    fun `two users never receive the same token`() {
        val a = codec.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 3600)
        val b = codec.issue(SessionPrincipal(sub = "user-b"), ttlSeconds = 3600)

        assertNotEquals(a.accessToken, b.accessToken)
    }

    @Test
    fun `the token does not contain the subject in the clear`() {
        val session = codec.issue(SessionPrincipal(sub = "user-a"), ttlSeconds = 3600)

        assertTrue(
            "the subject is a claim, not a readable field",
            !session.accessToken.contains("user-a")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a signing key shorter than the minimum is refused`() {
        SessionTokenCodec("too-short".toByteArray())
    }
}
