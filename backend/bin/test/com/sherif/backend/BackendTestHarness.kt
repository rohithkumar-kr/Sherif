package com.sherif.backend

import com.sherif.backend.ai.AiOperation
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * A running [SherifBackendServer] on an ephemeral port, plus a tiny HTTP client
 * aimed at it.
 *
 * Tests use this rather than calling handler methods directly so that the
 * status codes, headers and bodies a real client would see are the thing under
 * assertion.
 */
class BackendTestHarness(
    gateway: com.sherif.backend.ai.GeminiGateway =
        BackendTestDoubles.RecordingGeminiGateway(),
    identityVerifier: com.sherif.backend.auth.IdentityTokenVerifier =
        BackendTestDoubles.FakeIdentityVerifier(),
    sessionCodec: com.sherif.backend.auth.SessionTokenCodec = BackendTestDoubles.codec(),
    rateLimiter: com.sherif.backend.http.RateLimiter = com.sherif.backend.http.RateLimiter(),
    logger: com.sherif.backend.http.SafeLogger = com.sherif.backend.http.NoopSafeLogger,
    sessionTtlSeconds: Long = 3600,
    /**
     * The production state. A test that wants the development route must ask
     * for it explicitly, so the default harness in the whole suite is a server
     * that has never heard of development sign-in.
     */
    devAuthEnabled: Boolean = false
) {

    val gateway = gateway
    val identityVerifier = identityVerifier

    private val server = SherifBackendServer(
        sessionCodec = sessionCodec,
        identityVerifier = identityVerifier,
        geminiGateway = gateway,
        rateLimiter = rateLimiter,
        logger = logger,
        sessionTtlSeconds = sessionTtlSeconds,
        devSessionIssuer = if (devAuthEnabled) {
            com.sherif.backend.auth.DevSessionIssuer(sessionCodec, sessionTtlSeconds)
        } else {
            null
        }
    ).start()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    val baseUrl: String = "http://127.0.0.1:${server.port()}"

    fun stop() = server.stop()

    fun sessionFor(sub: String): String {
        val body = """{"idToken":"${BackendTestDoubles.idTokenFor(sub)}"}"""
        val response = post(SherifBackendServer.SESSION_PATH, body, token = null)
        check(response.statusCode() == 200) {
            "session exchange failed with ${response.statusCode()}: ${response.body()}"
        }
        return parsed(response.body())["accessToken"]!!
            .let { (it as JsonPrimitive).content }
    }

    fun post(
        path: String,
        body: String,
        token: String?,
        contentType: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", contentType)
        token?.let { builder.header("Authorization", "Bearer $it") }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        builder.POST(HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun postRawAuthorization(
        path: String,
        body: String,
        authorization: String
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", authorization)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    fun get(path: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
        token?.let { builder.header("Authorization", "Bearer $it") }
        builder.GET()
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {

        fun parsed(body: String): JsonObject =
            SherifJson.parseToJsonElement(body) as JsonObject

        fun errorCode(response: HttpResponse<String>): String? {
            val root = runCatching { parsed(response.body()) }.getOrNull() ?: return null
            val error = root["error"] as? JsonObject ?: return null
            return (error["code"] as? JsonPrimitive)?.content
        }

        fun bodyOf(operation: AiOperation): String = BackendTestDoubles.aiRequestBody()
    }
}
