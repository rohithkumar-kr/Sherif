package com.sherif.backend

import com.sherif.backend.auth.SessionTokenCodec
import java.util.Base64

/**
 * Validated, secret-free view of the process environment.
 *
 * Every secret arrives from the environment and nowhere else:
 *
 * ```
 * GEMINI_API_KEY        the Google credential; the single reason this process exists
 * SHERIF_SESSION_KEY    HMAC key for SHERIF access tokens, >= 32 bytes, base64 or raw
 * GOOGLE_CLIENT_ID      the Android OAuth client whose ID tokens are accepted
 * SHERIF_PORT           listen port, default 8080
 * SHERIF_BIND_HOST      listen address, default 127.0.0.1
 * SHERIF_DEV_AUTH_ENABLED  exactly "true" to expose the development session route, default off
 * ```
 *
 * There is no config file, no committed property and no fallback default. A
 * missing or too-short key is a startup failure, not a warning: a server that
 * quietly came up with a weak key would be precisely the "security theater"
 * this design exists to avoid.
 *
 * [DEFAULT_BIND_HOST] stays on loopback, so the shipped default is the
 * restrictive one. Widening the bind is a deliberate, per-machine act because
 * an Android emulator cannot reach the host's `127.0.0.1`: on that host an
 * operator has to set `SHERIF_BIND_HOST=0.0.0.0` for the app to see it at all.
 */
data class BackendConfig(
    val geminiApiKey: String,
    val sessionKey: ByteArray,
    val googleClientId: String,
    val port: Int,
    val bindHost: String = DEFAULT_BIND_HOST,
    val devAuthEnabled: Boolean = false
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is BackendConfig &&
            geminiApiKey == other.geminiApiKey &&
            sessionKey.contentEquals(other.sessionKey) &&
            googleClientId == other.googleClientId &&
            port == other.port &&
            bindHost == other.bindHost &&
            devAuthEnabled == other.devAuthEnabled)

    override fun hashCode(): Int {
        var result = geminiApiKey.hashCode()
        result = 31 * result + sessionKey.contentHashCode()
        result = 31 * result + googleClientId.hashCode()
        result = 31 * result + port
        result = 31 * result + bindHost.hashCode()
        result = 31 * result + devAuthEnabled.hashCode()
        return result
    }
}

const val ENV_GEMINI_API_KEY = "GEMINI_API_KEY"
const val ENV_SESSION_KEY = "SHERIF_SESSION_KEY"
const val ENV_GOOGLE_CLIENT_ID = "GOOGLE_CLIENT_ID"
const val ENV_PORT = "SHERIF_PORT"
const val ENV_BIND_HOST = "SHERIF_BIND_HOST"

/**
 * Opt-in switch for the development session route.
 *
 * Defaults to disabled and is enabled by exactly one value. `TRUE`, `1`, `yes`
 * and a typo all leave it off, because the failure mode of a permissive parse
 * here is a publicly reachable route that hands out a session to whoever asks.
 * Being too strict costs an operator one line of documentation; being too loose
 * costs a production deployment.
 */
const val ENV_DEV_AUTH_ENABLED = "SHERIF_DEV_AUTH_ENABLED"
const val DEV_AUTH_ENABLED_VALUE = "true"

const val DEFAULT_PORT = 8080
const val DEFAULT_BIND_HOST = "127.0.0.1"

/**
 * Parses and validates the environment.
 *
 * Every problem is collected before anything is thrown. The previous version
 * used `require`, which stops at the first failure, so an operator with a fresh
 * environment discovered one missing variable per restart -- and the natural
 * reaction to a server that will not start is to assume the thing that is
 * actually wrong is the thing it complained about last. Naming all of them at
 * once costs nothing and removes the guesswork.
 *
 * @throws IllegalArgumentException listing every operator-facing reason, with no
 *   value from the environment echoed back into the message.
 */
fun backendConfigFrom(env: Map<String, String>): BackendConfig {
    val problems = mutableListOf<String>()

    val geminiApiKey = env[ENV_GEMINI_API_KEY]?.trim().orEmpty()
    if (geminiApiKey.isEmpty()) problems += "$ENV_GEMINI_API_KEY is required"

    val rawSessionKey = env[ENV_SESSION_KEY]?.trim().orEmpty()
    val sessionKey = if (rawSessionKey.isEmpty()) {
        problems += "$ENV_SESSION_KEY is required"
        ByteArray(0)
    } else {
        decodeSessionKey(rawSessionKey).also { decoded ->
            if (decoded.size < SessionTokenCodec.MIN_KEY_BYTES) {
                problems += "$ENV_SESSION_KEY must decode to at least ${SessionTokenCodec.MIN_KEY_BYTES} bytes"
            }
        }
    }

    val clientId = env[ENV_GOOGLE_CLIENT_ID]?.trim().orEmpty()
    if (clientId.isEmpty()) problems += "$ENV_GOOGLE_CLIENT_ID is required"

    // A malformed port used to be indistinguishable from an absent one: both
    // produced `null` from `toIntOrNull`, and both fell back to the default. A
    // typo in SHERIF_PORT was therefore silently ignored, and the server came up
    // on a port nobody was expecting -- reachable in principle, in practice not.
    val port = when (val rawPort = env[ENV_PORT]?.trim()) {
        null, "" -> DEFAULT_PORT
        else -> rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            problems += "$ENV_PORT must be a whole number between 1 and 65535"
            DEFAULT_PORT
        }
    }

    if (problems.isNotEmpty()) {
        throw IllegalArgumentException(
            buildString {
                append("SHERIF backend configuration is incomplete:")
                problems.forEach { problem -> append("\n  - ").append(problem) }
            }
        )
    }

    val bindHost = env[ENV_BIND_HOST]?.trim().orEmpty()
        .ifEmpty { DEFAULT_BIND_HOST }

    return BackendConfig(
        geminiApiKey = geminiApiKey,
        sessionKey = sessionKey,
        googleClientId = clientId,
        port = port,
        bindHost = bindHost,
        devAuthEnabled = devAuthEnabledFrom(env)
    )
}

/**
 * True only for an exact, case-sensitive `true`.
 *
 * Deliberately stricter than the rest of the config. Every other variable
 * answers "is this set", and a near-miss there is a startup error an operator
 * will read. This one answers "is a session-minting route going live", where the
 * near-misses (`1`, `yes`, `TRUE`, `on`) are the dangerous direction, so they
 * resolve to off. An absent or blank value is off, which is the safe default.
 */
fun devAuthEnabledFrom(env: Map<String, String>): Boolean =
    env[ENV_DEV_AUTH_ENABLED]?.trim() == DEV_AUTH_ENABLED_VALUE

/**
 * Accepts a base64 key, falling back to the raw bytes of an unencoded value so
 * operators are not forced into one format.
 */
private fun decodeSessionKey(raw: String): ByteArray {
    val base64Decoded = runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
    return base64Decoded?.takeIf { it.isNotEmpty() } ?: raw.toByteArray(Charsets.UTF_8)
}
