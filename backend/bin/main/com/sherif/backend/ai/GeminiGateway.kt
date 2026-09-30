package com.sherif.backend.ai

import com.sherif.backend.BackendError
import com.sherif.backend.BackendException
import com.sherif.backend.SherifJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The backend's single outbound AI call.
 *
 * An interface so tests can prove the authorization and validation rules
 * without a network, and so the credential can be owned in exactly one place.
 */
interface GeminiGateway {

    /**
     * Forwards a validated request to Gemini and returns the raw upstream body.
     *
     * Failures are mapped to [BackendError] values that are safe to serialise;
     * the upstream status, body and the API key never leave this layer.
     */
    fun generate(request: ValidatedAiRequest): String
}

/**
 * Calls Google's Gemini REST API with the server-held credential.
 *
 * The key is read once at construction and held only here. It is sent in the
 * `x-goog-api-key` header, is never logged, never echoed in an error, and has no
 * getter, so no other class can read it. A caller cannot influence the request
 * line, because the model name and the path are constants and the body is
 * rebuilt from validated fields.
 */
class HttpGeminiGateway(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val httpClient: HttpClient = defaultClient(),
    private val timeout: Duration = Duration.ofSeconds(60)
) : GeminiGateway {

    override fun generate(request: ValidatedAiRequest): String {
        if (apiKey.isBlank()) throw BackendException(BackendError.NOT_CONFIGURED)

        val httpRequest = HttpRequest.newBuilder(URI.create(endpoint()))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(renderBody(request)))
            .build()

        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw BackendException(BackendError.UPSTREAM_UNAVAILABLE, cause = e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BackendException(BackendError.UPSTREAM_UNAVAILABLE, cause = e)
        }

        return when {
            response.statusCode() in 200..299 -> response.body()
            else -> throw BackendException(errorFor(response.statusCode()))
        }
    }

    /**
     * Rebuilds the upstream body from validated fields only.
     *
     * Rebuilding rather than forwarding is what stops a client from smuggling
     * additional top-level Gemini parameters past the validator.
     *
     * `internal` rather than private so tests can assert the upstream shape
     * without a network call.
     */
    internal fun renderBody(request: ValidatedAiRequest): String {
        val generationConfig = buildJsonObject {
            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("maxOutputTokens", it) }
            request.responseMimeType?.let { put("responseMimeType", it) }
            request.responseSchemaJson?.let {
                put("responseSchema", SherifJson.parseToJsonElement(it))
            }
        }

        val body = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", request.prompt) })
                                }
                            )
                        }
                    )
                }
            )
            put("generationConfig", generationConfig)
        }
        return body.toString()
    }

    private fun endpoint(): String =
        "$GEMINI_BASE_URL/v1beta/models/$model:generateContent"

    companion object {
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-flash-latest"

        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build()

        /**
         * Translates an upstream status into one of this API's own errors.
         *
         * The previous version collapsed the whole 4xx range into
         * [BackendError.UPSTREAM_REJECTED], which flattened three situations
         * that need different responses into one:
         *
         *  * **401/403** mean the key is wrong, revoked, or lacks access to the
         *    model. Nothing the user does changes that and no retry succeeds,
         *    but the old code told the app the AI service "rejected the request"
         *    and invited a retry, so a server with a dead key looked like a
         *    flaky service indefinitely. It is reported as
         *    [BackendError.NOT_CONFIGURED], which is the truth.
         *  * **429** is the upstream quota, not this server's own limiter. They
         *    share a status, so the distinction is carried by the error.
         *  * **404** is a model name that does not exist and **400** is a body
         *    the API rejected -- faults on this side, reported as
         *    [BackendError.NOT_CONFIGURED] and [BackendError.INTERNAL] so
         *    neither is presented to the user as their own problem.
         *
         * In the companion so it can be asserted without constructing a
         * gateway, which would mean building an [HttpClient] and its selector
         * thread in a unit test.
         *
         * The upstream status, body and key never leave this layer, so a client
         * learns *which* class of problem occurred without learning anything
         * about the credential.
         */
        internal fun errorFor(statusCode: Int): BackendError = when (statusCode) {
            400 -> BackendError.INTERNAL
            401, 403, 404 -> BackendError.NOT_CONFIGURED
            429 -> BackendError.RATE_LIMITED
            in 500..599 -> BackendError.UPSTREAM_UNAVAILABLE
            in 400..499 -> BackendError.UPSTREAM_REJECTED
            else -> BackendError.UPSTREAM_UNAVAILABLE
        }
    }
}
