package com.sherif.backend

import com.sherif.backend.ai.ValidatedAiRequest
import com.sherif.backend.auth.IdentityTokenVerifier
import com.sherif.backend.auth.SessionPrincipal
import com.sherif.backend.auth.SessionTokenCodec

/**
 * Test doubles for the backend's two trust boundaries.
 *
 * The identity double is the important one. Production derives identity from
 * Google's signature via [com.sherif.backend.auth.GoogleIdTokenVerifier]; here
 * a token of the form `id:<sub>` stands in for one Google has signed. The token
 * *string* is never used as a user id anywhere, so a test that can make User A
 * see User B's data would mean a real bug, not a clever fake.
 */
object BackendTestDoubles {

    /** Builds the stand-in for a Google-signed ID token for [sub]. */
    fun idTokenFor(sub: String): String = "id:$sub"

    /**
     * Accepts `id:<sub>` and refuses everything else.
     *
     * Mirrors the production contract: a token it does not recognise is an
     * authentication failure, not a default identity.
     */
    class FakeIdentityVerifier(
        private val revoked: Set<String> = emptySet()
    ) : IdentityTokenVerifier {

        val seenTokens = mutableListOf<String>()

        override fun verify(idToken: String): SessionPrincipal {
            seenTokens += idToken
            if (idToken in revoked) {
                throw BackendException(BackendError.IDENTITY_TOKEN_INVALID)
            }
            if (!idToken.startsWith(PREFIX)) {
                throw BackendException(BackendError.IDENTITY_TOKEN_INVALID)
            }
            val sub = idToken.removePrefix(PREFIX)
            if (sub.isBlank()) throw BackendException(BackendError.IDENTITY_TOKEN_INVALID)
            return SessionPrincipal(sub = sub, email = "$sub@example.test", name = "Test $sub")
        }

        companion object {
            const val PREFIX = "id:"
        }
    }

    /**
     * Records what reached the metered boundary and replies with a fixed body.
     *
     * `calls` is the assertion that matters: a rejected request must never make
     * this non-zero, because that is the same as saying the API key was never
     * spent.
     */
    class RecordingGeminiGateway(
        private val response: String = DEFAULT_RESPONSE,
        private val failure: BackendError? = null
    ) : com.sherif.backend.ai.GeminiGateway {

        var calls: Int = 0
            private set
        val received = mutableListOf<ValidatedAiRequest>()

        override fun generate(request: ValidatedAiRequest): String {
            calls++
            received += request
            failure?.let { throw BackendException(it) }
            return response
        }

        companion object {
            const val DEFAULT_RESPONSE =
                """{"candidates":[{"content":{"parts":[{"text":"{\"candidateName\":\"Test\"}"}],"role":"model"}}]}"""
        }
    }

    /** A deterministic 40-byte signing key for tests. */
    fun testSigningKey(): ByteArray = TEST_SIGNING_KEY.toByteArray(Charsets.UTF_8)

    const val TEST_SIGNING_KEY = "sherif-test-signing-key-0123456789abcdef"

    fun codec(clockMillis: () -> Long = { 1_700_000_000_000L }): SessionTokenCodec =
        SessionTokenCodec(testSigningKey(), clock = clockMillis)

    /** A minimal valid AI request body for [operation]. */
    fun aiRequestBody(
        prompt: String = "Explain Kotlin coroutines.",
        mimeType: String? = "application/json",
        includeSchema: Boolean = true
    ): String {
        val generation = buildString {
            append("""{"temperature":0.7,"maxOutputTokens":1024""")
            mimeType?.let { append(""","responseMimeType":"$it"""") }
            if (includeSchema) {
                append(""","responseSchema":{"type":"object","properties":{"q":{"type":"string"}}}""")
            }
            append("}")
        }
        return """{"contents":[{"parts":[{"text":${quote(prompt)}}]}],"generationConfig":$generation}"""
    }

    /** A body that tries to assert an identity the caller does not own. */
    fun aiRequestBodyWithUserIdClaim(userId: String, prompt: String = "Explain Kotlin."): String =
        aiRequestBody(prompt = prompt)
            .dropLast(1)
            .plus(""","userId":"$userId"}""")

    fun quote(value: String): String {
        val builder = StringBuilder("\"")
        value.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char < ' ') {
                    builder.append("\\u").append(String.format("%04x", char.code))
                } else {
                    builder.append(char)
                }
            }
        }
        return builder.append('"').toString()
    }
}
