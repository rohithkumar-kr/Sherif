package com.sherif.backend

import com.sherif.backend.auth.SessionTokenCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RULE 2: the backend is configured from the environment and refuses to come up
 * half-configured. A default secret would be worse than a crash.
 */
class BackendConfigTest {

    private fun validEnv() = mapOf(
        ENV_GEMINI_API_KEY to "test-gemini-key",
        ENV_SESSION_KEY to "c2hlcmlmLXRlc3Qtc2lnbmluZy1rZXktMDEyMzQ1Njc4OWFiY2RlZg==",
        ENV_GOOGLE_CLIENT_ID to "1234567890-abc.apps.googleusercontent.com"
    )

    @Test
    fun `a complete environment produces a usable config`() {
        val config = backendConfigFrom(validEnv())

        assertEquals("test-gemini-key", config.geminiApiKey)
        assertEquals("1234567890-abc.apps.googleusercontent.com", config.googleClientId)
        assertTrue(config.sessionKey.size >= SessionTokenCodec.MIN_KEY_BYTES)
    }

    @Test
    fun `the default port is used when none is given`() {
        assertEquals(DEFAULT_PORT, backendConfigFrom(validEnv()).port)
    }

    @Test
    fun `an explicit port is honoured`() {
        assertEquals(9090, backendConfigFrom(validEnv() + (ENV_PORT to "9090")).port)
    }

    @Test
    fun `a missing gemini key is a startup failure`() {
        val failure = failureFrom(validEnv() - ENV_GEMINI_API_KEY)

        assertTrue(failure.contains(ENV_GEMINI_API_KEY))
    }

    @Test
    fun `a missing session key is a startup failure`() {
        val failure = failureFrom(validEnv() - ENV_SESSION_KEY)

        assertTrue(failure.contains(ENV_SESSION_KEY))
    }

    @Test
    fun `a missing google client id is a startup failure`() {
        val failure = failureFrom(validEnv() - ENV_GOOGLE_CLIENT_ID)

        assertTrue(failure.contains(ENV_GOOGLE_CLIENT_ID))
    }

    @Test
    fun `a short session key is refused rather than silently padded`() {
        val failure = failureFrom(validEnv() + (ENV_SESSION_KEY to "short"))

        assertTrue(failure.contains("at least"))
    }

    @Test
    fun `an invalid port is refused`() {
        val failure = failureFrom(validEnv() + (ENV_PORT to "70000"))

        assertTrue(failure.contains(ENV_PORT))
    }

    @Test
    fun `an unparseable port is refused rather than silently replaced by the default`() {
        // A typo used to look identical to an absent value -- both produced null
        // from toIntOrNull -- so the server came up on 8080 while the operator
        // believed they had chosen something else.
        val failure = failureFrom(validEnv() + (ENV_PORT to "eighty-eighty"))

        assertTrue(failure.contains(ENV_PORT))
    }

    @Test
    fun `a fractional port is refused`() {
        val failure = failureFrom(validEnv() + (ENV_PORT to "80.5"))

        assertTrue(failure.contains(ENV_PORT))
    }

    @Test
    fun `a blank port falls back to the default rather than refusing to start`() {
        assertEquals(DEFAULT_PORT, backendConfigFrom(validEnv() + (ENV_PORT to "   ")).port)
    }

    @Test
    fun `every missing value is reported in one failure`() {
        // One problem per restart makes an operator guess which of several
        // missing variables to go and set next.
        val failure = failureFrom(emptyMap())

        listOf(ENV_GEMINI_API_KEY, ENV_SESSION_KEY, ENV_GOOGLE_CLIENT_ID).forEach { name ->
            assertTrue("expected $name in: $failure", failure.contains(name))
        }
    }

    @Test
    fun `a bad port is reported alongside a missing key rather than masked by it`() {
        val failure = failureFrom(validEnv() - ENV_GEMINI_API_KEY + (ENV_PORT to "nope"))

        assertTrue(failure.contains(ENV_GEMINI_API_KEY))
        assertTrue(failure.contains(ENV_PORT))
    }

    @Test
    fun `a configuration failure never echoes the value it rejected`() {
        val almostValid = "super-secret-gemini-key"

        val failure = failureFrom(validEnv() - ENV_GOOGLE_CLIENT_ID + (ENV_GEMINI_API_KEY to almostValid))

        assertTrue(failure.contains(ENV_GOOGLE_CLIENT_ID))
        assertTrue(
            "the rejected value must not appear in the message: $failure",
            !failure.contains(almostValid)
        )
    }

    @Test
    fun `the server binds to loopback unless told otherwise`() {
        assertEquals(DEFAULT_BIND_HOST, backendConfigFrom(validEnv()).bindHost)
    }

    @Test
    fun `an explicit bind host is honoured so a device can reach the server`() {
        val config = backendConfigFrom(validEnv() + (ENV_BIND_HOST to "0.0.0.0"))

        assertEquals("0.0.0.0", config.bindHost)
    }

    @Test
    fun `a blank bind host falls back to loopback rather than binding nowhere`() {
        val config = backendConfigFrom(validEnv() + (ENV_BIND_HOST to "   "))

        assertEquals(DEFAULT_BIND_HOST, config.bindHost)
    }

    @Test
    fun `a raw session key is accepted as well as a base64 one`() {
        val raw = "a-raw-session-key-that-is-long-enough-32b"

        val config = backendConfigFrom(validEnv() + (ENV_SESSION_KEY to raw))

        assertTrue(config.sessionKey.size >= SessionTokenCodec.MIN_KEY_BYTES)
    }

    @Test
    fun `an empty environment yields no config at all`() {
        assertTrue(failureFrom(emptyMap()).isNotEmpty())
    }

    private fun failureFrom(env: Map<String, String>): String = try {
        backendConfigFrom(env)
        ""
    } catch (e: IllegalArgumentException) {
        e.message.orEmpty()
    }
}
