package com.sherif.backend

import com.sherif.backend.ai.AiOperation
import com.sherif.backend.ai.AiRequestValidator
import com.sherif.backend.ai.GeminiGateway
import com.sherif.backend.ai.MAX_AUTH_BODY
import com.sherif.backend.ai.ValidatedAiRequest
import com.sherif.backend.auth.DevSessionIssuer
import com.sherif.backend.auth.GoogleIdTokenVerifier
import com.sherif.backend.auth.IdentityTokenVerifier
import com.sherif.backend.auth.IssuedSession
import com.sherif.backend.auth.SessionPrincipal
import com.sherif.backend.auth.SessionTokenCodec
import com.sherif.backend.http.NoopSafeLogger
import com.sherif.backend.http.RateLimiter
import com.sherif.backend.http.SafeLogger
import com.sherif.backend.http.StderrSafeLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The SHERIF backend.
 *
 * ```
 * Android --HTTPS + Authorization: Bearer--> SherifBackendServer --HTTPS--> Gemini
 * ```
 *
 * This process is the reason the Android app can be shipped: the Gemini
 * credential exists here and nowhere else. Three properties are load-bearing.
 *
 * 1. **Identity is derived, never supplied.** The only place a `userId` enters
 *    the system is [IdentityTokenVerifier], which checks Google's signature.
 *    [SessionTokenCodec] then mints a token bound to that verified subject, and
 *    every AI route recovers the user from that token. A caller who edits the
 *    body, adds a `userId` field, or replays another user's token is either
 *    ignored or rejected -- there is no request field that can change who the
 *    caller is.
 * 2. **Validation precedes the metered call.** [AiRequestValidator] runs before
 *    [GeminiGateway], so a malformed or oversized request is refused without
 *    spending a token.
 * 3. **Errors are enumerated.** Every failure leaves as one [BackendError] with
 *    a fixed status and a code, so upstream vendor text, internal URLs and the
 *    API key cannot reach a client.
 */
class SherifBackendServer(
    private val sessionCodec: SessionTokenCodec,
    private val identityVerifier: IdentityTokenVerifier,
    private val geminiGateway: GeminiGateway,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val logger: SafeLogger = NoopSafeLogger,
    private val sessionTtlSeconds: Long = SessionTokenCodec.DEFAULT_TTL_SECONDS,
    private val maxBodyBytes: Int = GLOBAL_MAX_BODY_BYTES,
    private val port: Int = BIND_PORT,
    /**
     * Loopback by default. An Android emulator reaches the development
     * machine at 10.0.0.2/10.0.2.2 and never at the host's `127.0.0.1`, so
     * running the backend for a device or emulator is an explicit choice of a
     * wider address, made by whoever runs the server.
     */
    private val bindHost: String = BIND_HOST,
    /**
     * Present only when the operator set `SHERIF_DEV_AUTH_ENABLED=true`.
     *
     * Null is the production and default state, and the development route is
     * then not merely refused but *unreachable*: the path is not matched at
     * all, so it falls through to the same `MALFORMED_REQUEST` as any unknown
     * path. An operator who forgets the variable gets no hint that a
     * development route exists.
     */
    private val devSessionIssuer: DevSessionIssuer? = null
) {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(bindHost, port), BACKLOG)

    /** Starts listening. Call [stop] to release the port. */
    fun start(): SherifBackendServer {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = Executors.newFixedThreadPool(WORKER_THREADS)
        server.start()
        return this
    }

    fun port(): Int = server.address.port

    fun stop() {
        server.stop(0)
    }

    // --- Routing -------------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        val startedAt = System.nanoTime()
        val path = exchange.requestURI.path
        var principal: SessionPrincipal? = null
        var status = 500

        try {
            when (path) {
                HEALTH_PATH -> {
                    status = respondHealth(exchange)
                }
                SESSION_PATH -> {
                    status = handleSessionExchange(exchange)
                }
                else -> {
                    val operation = AiOperation.forPath(path)
                    when {
                        // Checked before the AI table, and only when the issuer
                        // exists. With no issuer this is a normal unknown path.
                        devSessionIssuer != null && path == DEV_SESSION_PATH -> {
                            status = handleDevSessionExchange(exchange, devSessionIssuer)
                        }
                        operation == null -> {
                            status = respondError(exchange, BackendError.MALFORMED_REQUEST)
                        }
                        else -> {
                            principal = requirePrincipal(exchange)
                            status = handleAiOperation(exchange, operation, principal)
                        }
                    }
                }
            }
        } catch (e: BackendException) {
            status = respondError(exchange, e.error, e.retryAfterSeconds)
        } catch (e: Exception) {
            logger.failure("unhandled error path=$path", e)
            status = respondError(exchange, BackendError.INTERNAL)
        } finally {
            val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
            logger.request(exchange.requestMethod, path, status, durationMillis, principal?.sub)
            exchange.close()
        }
    }

    /**
     * Exchanges a verified Google ID token for a SHERIF session.
     *
     * This is the only unauthenticated route that can lead to a session, and it
     * can only do so by producing a token Google signed.
     */
    private fun handleSessionExchange(exchange: HttpExchange): Int {
        if (exchange.requestMethod != "POST") return respondError(exchange, BackendError.MALFORMED_REQUEST)

        val body = readBody(exchange, MAX_AUTH_BODY).toString(Charsets.UTF_8)
        val root = runCatching { SherifJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return respondError(exchange, BackendError.MALFORMED_REQUEST)
        // Absent field is a malformed request; a present-but-unverifiable
        // token is an authentication failure. The distinction lets the client
        // tell "you sent the wrong shape" from "your sign-in was rejected".
        val idToken = root["idToken"]?.stringOrNull()
            ?: return respondError(exchange, BackendError.MALFORMED_REQUEST)

        val verified = identityVerifier.verify(idToken)
        val session = sessionCodec.issue(verified, sessionTtlSeconds)
        return respondRawJson(exchange, 200, sessionJson(session))
    }

    /**
     * Issues the single development session, for a development server only.
     *
     * Reachable only when [devSessionIssuer] is non-null, i.e. when
     * `SHERIF_DEV_AUTH_ENABLED=true`. It reads no request data at all: there is
     * no body to parse and no identity to extract, because the identity is a
     * constant in [DevSessionIssuer]. That is the reason this cannot be turned
     * into a general "log in as anybody" endpoint.
     *
     * The response is an ordinary [IssuedSession], so the client stores it in
     * its ordinary session store and every later request is authenticated the
     * same way a real user's is.
     */
    private fun handleDevSessionExchange(exchange: HttpExchange, issuer: DevSessionIssuer): Int {
        if (exchange.requestMethod != "POST") return respondError(exchange, BackendError.MALFORMED_REQUEST)

        // Read and discard so an unread body cannot stall the connection. The
        // contents are intentionally not inspected.
        readBody(exchange, MAX_AUTH_BODY)

        val session = issuer.issue()
        logger.info("issued development session sub=${DevSessionIssuer.DEV_PRINCIPAL_SUB}")
        return respondRawJson(exchange, 200, sessionJson(session))
    }
    /**
     * Authorises, validates, rate-limits, then calls Gemini.
     *
     * The order matters: rate limiting precedes validation so a flood of
     * oversized bodies is stopped cheaply, and both precede the upstream call.
     */
    private fun handleAiOperation(
        exchange: HttpExchange,
        operation: AiOperation,
        principal: SessionPrincipal
    ): Int {
        if (exchange.requestMethod != "POST") return respondError(exchange, BackendError.MALFORMED_REQUEST)

        rateLimiter.check(principal.sub)

        val body = readBody(exchange, operation.maxBodyBytes)
        val validated = AiRequestValidator.validate(operation, body)

        val upstream = geminiGateway.generate(validated)
        return respondRawJson(exchange, 200, upstream)
    }

    // --- Authentication ------------------------------------------------------

    /**
     * Recovers the caller from the bearer token.
     *
     * The request body is never consulted for identity, which is what makes a
     * client-supplied `userId` inert rather than merely discouraged.
     */
    private fun requirePrincipal(exchange: HttpExchange): SessionPrincipal {
        val header = exchange.requestHeaders.getFirst("Authorization")
            ?: throw BackendException(BackendError.UNAUTHENTICATED)
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            throw BackendException(BackendError.UNAUTHENTICATED)
        }
        return sessionCodec.verify(header.substring(BEARER_PREFIX.length).trim())
    }

    // --- Request / response plumbing ----------------------------------------

    /**
     * Reads at most [limit] bytes, refusing anything larger.
     *
     * The cap is enforced during the read rather than by trusting
     * `Content-Length`, so a lying or chunked header cannot get past it.
     */
    private fun readBody(exchange: HttpExchange, limit: Int): ByteArray {
        val stream = exchange.requestBody
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = try {
                stream.read(chunk)
            } catch (e: IOException) {
                throw BackendException(BackendError.MALFORMED_REQUEST, cause = e)
            }
            if (read <= 0) break
            total += read
            if (total > limit) throw BackendException(BackendError.PAYLOAD_TOO_LARGE)
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun respondHealth(exchange: HttpExchange): Int {
        // Deliberately reports liveness only. Nothing here reflects the state
        // of the Gemini credential or the identity configuration.
        return respondRawJson(exchange, 200, """{"status":"ok"}""")
    }

    private fun respondRawJson(exchange: HttpExchange, status: Int, body: String): Int {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        return status
    }

    /**
     * Writes the one error shape.
     *
     * `clientMessage` is never sent: the client owns the wording, so a change
     * here cannot silently alter what a user reads.
     */
    private fun respondError(
        exchange: HttpExchange,
        error: BackendError,
        retryAfterSeconds: Int? = null
    ): Int {
        retryAfterSeconds?.let { exchange.responseHeaders.add("Retry-After", it.toString()) }
        val payload = SherifJson.encodeToString(
            ErrorResponse.serializer(),
            ErrorResponse(
                error = ErrorBody(
                    code = error.name,
                    retryAfterSeconds = retryAfterSeconds
                )
            )
        )
        return respondRawJson(exchange, error.status, payload)
    }

    private fun sessionJson(session: IssuedSession): String = SherifJson.encodeToString(
        IssuedSession.serializer(),
        session
    )

    companion object {
        const val HEALTH_PATH = "/health"
        const val SESSION_PATH = "/v1/auth/session"

        /**
         * Development-only session route.
         *
         * Distinct from [SESSION_PATH] so that no client code, interceptor or
         * allowlist which reasons about the Google exchange can be widened to
         * cover this one by accident.
         */
        const val DEV_SESSION_PATH = "/v1/auth/dev-session"
        const val BEARER_PREFIX = "Bearer "

        const val GLOBAL_MAX_BODY_BYTES = com.sherif.backend.ai.MAX_RESUME_BODY
        const val BIND_HOST = com.sherif.backend.DEFAULT_BIND_HOST
        const val BIND_PORT = 0
        const val BACKLOG = 16
        const val WORKER_THREADS = 4

        fun productionLogger(): SafeLogger = StderrSafeLogger()

        fun productionIdentityVerifier(expectedClientId: String): IdentityTokenVerifier =
            GoogleIdTokenVerifier(expectedClientId = expectedClientId)
    }
}

/** Returns the string content, or null for JSON null / a non-primitive. */
private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
