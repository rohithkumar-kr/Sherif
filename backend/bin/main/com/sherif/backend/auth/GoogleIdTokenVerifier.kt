package com.sherif.backend.auth

import com.sherif.backend.BackendError
import com.sherif.backend.BackendException
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a Google ID token into a [SessionPrincipal], or refuses.
 *
 * This is the *only* place SHERIF learns who a caller is. It validates Google's
 * signature against Google's published JWKS, so a caller cannot assert an
 * identity of their choosing without Google's private key.
 */
interface IdentityTokenVerifier {

    /**
     * @throws BackendException with [BackendError.IDENTITY_TOKEN_INVALID] when
     *   the token is absent, malformed, wrongly signed, expired, or issued for
     *   another client.
     */
    fun verify(idToken: String): SessionPrincipal
}

/**
 * Verifies Google Identity Services ID tokens (RS256) against Google's JWKS.
 *
 * Checks, in order: three-part structure, `alg` and `kid`, RSA signature over
 * `header.payload`, then the registered claims `iss`, `aud`, `exp` and `iat`.
 *
 * The signature check is what makes this real. Every other check here is
 * cosmetic on its own: without verifying against Google's key, a caller could
 * write `{"sub":"someone-else"}` and sign it with a key they generated.
 */
class GoogleIdTokenVerifier(
    private val expectedClientId: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val jwksUri: String = GOOGLE_JWKS_URI,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jwksCache: JwksCache = JwksCache(httpClient, jwksUri)
) : IdentityTokenVerifier {

    private val json = Json { ignoreUnknownKeys = true }
    private val urlDecoder = Base64.getUrlDecoder()

    override fun verify(idToken: String): SessionPrincipal {
        val parts = idToken.split('.')
        if (parts.size != 3) throw invalid("not a three-part token")

        val header = decodeSegment(parts[0])
        val payload = decodeSegment(parts[1])
        val signatureBytes = decodeSegmentBytes(parts[2])

        val algorithm = header["alg"]?.jsonPrimitive?.content
        if (algorithm != SUPPORTED_ALGORITHM) throw invalid("unsupported algorithm")

        val kid = header["kid"]?.jsonPrimitive?.content
            ?: throw invalid("no key id")

        val key = jwksCache.findKey(kid) ?: throw invalid("unknown key id")

        if (!verifySignature(parts[0], parts[1], signatureBytes, key)) {
            throw invalid("signature mismatch")
        }

        verifyRegisteredClaims(payload)
        return SessionPrincipal(
            sub = payload.required("sub"),
            email = payload.optional("email"),
            name = payload.optional("name")
        )
    }

    private fun verifySignature(header: String, payload: String, signature: ByteArray, key: java.security.PublicKey): Boolean {
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(key)
        verifier.update("$header.$payload".toByteArray(Charsets.UTF_8))
        return verifier.verify(signature)
    }

    private fun verifyRegisteredClaims(payload: JsonObject) {
        val issuer = payload.required("iss")
        if (issuer !in ACCEPTED_ISSUERS) throw invalid("wrong issuer")

        val audience = payload.required("aud")
        if (audience != expectedClientId) throw invalid("wrong audience")

        val nowSeconds = clock() / 1000
        val expiry = payload.required("exp").toLongOrNull() ?: throw invalid("bad exp")
        if (expiry <= nowSeconds) throw invalid("token expired")

        // Rejects a token minted far in the past, which is the cheap way to
        // resurrect a leaked old token forever.
        val issuedAt = payload.optional("iat")?.toLongOrNull() ?: throw invalid("bad iat")
        if (issuedAt > nowSeconds + CLOCK_SKEW_SECONDS) throw invalid("issued in the future")
        if (nowSeconds - issuedAt > MAX_TOKEN_AGE_SECONDS) throw invalid("token too old")
    }

    private fun decodeSegment(segment: String): JsonObject = try {
        json.parseToJsonElement(String(decodeSegmentBytes(segment), Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        throw invalid("undecodable segment", e)
    }

    private fun decodeSegmentBytes(segment: String): ByteArray = try {
        urlDecoder.decode(segment)
    } catch (e: Exception) {
        throw invalid("bad base64url", e)
    }

    private fun invalid(reason: String, cause: Throwable? = null): BackendException =
        BackendException(BackendError.IDENTITY_TOKEN_INVALID, cause = cause ?: IllegalStateException(reason))

    private fun JsonObject.required(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNullSafe() ?: throw invalid("missing $name")

    private fun JsonObject.optional(name: String): String? = this[name]?.jsonPrimitive?.contentOrNullSafe()

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    companion object {
        const val SUPPORTED_ALGORITHM = "RS256"
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        const val GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs"

        val ACCEPTED_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")

        const val CLOCK_SKEW_SECONDS = 60L

        /** Google ID tokens are short-lived; anything older is a replay. */
        const val MAX_TOKEN_AGE_SECONDS = 24L * 60L * 60L

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        /** Builds an RSA public key from a JWKS entry, or null if unusable. */
        fun rsaPublicKey(n: String, e: String): java.security.PublicKey? = try {
            val modulus = unsignedBigInteger(n)
            val exponent = unsignedBigInteger(e)
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        } catch (e: Exception) {
            null
        }

        private fun unsignedBigInteger(value: String): BigInteger = BigInteger(1, Base64.getUrlDecoder().decode(value))
    }
}

/**
 * Caches Google's public keys so an ID-token check does not cost an outbound
 * request on every sign-in.
 *
 * Only public material is held. An unknown `kid` forces exactly one refresh,
 * which is how a newly rotated Google key is picked up without a restart.
 */
class JwksCache(
    private val httpClient: HttpClient,
    private val jwksUri: String,
    private val ttl: Duration = Duration.ofHours(1),
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var keys: Map<String, java.security.PublicKey> = emptyMap()
    private var loadedAtMillis: Long = 0

    @Synchronized
    fun findKey(kid: String): java.security.PublicKey? {
        if (isStale() || keys.isEmpty()) reload()
        keys[kid]?.let { return it }
        // A key id we have never seen is the signal that Google's set rotated.
        reload()
        return keys[kid]
    }

    private fun isStale(): Boolean = clock() - loadedAtMillis >= ttl.toMillis()

    private fun reload() {
        val request = HttpRequest.newBuilder(URI.create(jwksUri))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw BackendException(BackendError.IDENTITY_UNAVAILABLE, cause = e)
        }
        if (response.statusCode() != 200) {
            throw BackendException(BackendError.IDENTITY_UNAVAILABLE)
        }
        keys = parseKeys(response.body())
        loadedAtMillis = clock()
    }

    private fun parseKeys(body: String): Map<String, java.security.PublicKey> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw BackendException(BackendError.IDENTITY_UNAVAILABLE)
        val list = root["keys"]?.let { element ->
            runCatching { element.let { it as? kotlinx.serialization.json.JsonArray } }.getOrNull()
        } ?: return emptyMap()

        return list.mapNotNull { element ->
            val entry = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val kid = entry["kid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val n = entry["n"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val e = entry["e"]?.jsonPrimitive?.content ?: return@mapNotNull null
            GoogleIdTokenVerifier.rsaPublicKey(n, e)?.let { kid to it }
        }.toMap()
    }
}

/** Deterministic, non-reversible display tag for a user id in server logs. */
fun String.logFingerprint(): String = fingerprintOf(this)

/** Non-reversible short tag for a user id, safe to write to a log. */
fun fingerprintOf(userId: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(userId.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
