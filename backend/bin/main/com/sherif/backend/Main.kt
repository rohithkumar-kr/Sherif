package com.sherif.backend

import com.sherif.backend.ai.HttpGeminiGateway
import com.sherif.backend.auth.DevSessionIssuer
import com.sherif.backend.auth.SessionTokenCodec
import com.sherif.backend.http.RateLimiter
import kotlin.system.exitProcess

/**
 * Production entrypoint: assembles the trusted server from the environment.
 *
 * Nothing here has a default secret. If configuration is missing the process
 * exits with status 2 rather than starting in a degraded state.
 */
object Main {

    fun main(args: Array<String>) {
        val config = try {
            backendConfigFrom(System.getenv())
        } catch (e: IllegalArgumentException) {
            System.err.println("sherif backend misconfigured: ${e.message}")
            exitProcess(2)
        }

        SherifBackendServer(
            sessionCodec = SessionTokenCodec(config.sessionKey),
            identityVerifier = SherifBackendServer.productionIdentityVerifier(config.googleClientId),
            geminiGateway = HttpGeminiGateway(apiKey = config.geminiApiKey),
            rateLimiter = RateLimiter(),
            logger = SherifBackendServer.productionLogger(),
            port = config.port,
            bindHost = config.bindHost,
            // Null unless the operator opted in, which leaves the development
            // route unregistered. This is the only place the flag turns into a
            // reachable endpoint.
            devSessionIssuer = if (config.devAuthEnabled) {
                DevSessionIssuer(SessionTokenCodec(config.sessionKey))
            } else {
                null
            }
        ).start()

        System.err.println("sherif backend listening on ${config.bindHost}:${config.port}")

        // Announced on stderr rather than left implicit: a server that can hand
        // out a session without Google should never be discovered by a request
        // someone else makes.
        if (config.devAuthEnabled) {
            System.err.println(
                "WARNING: $ENV_DEV_AUTH_ENABLED is set; " +
                    "${SherifBackendServer.DEV_SESSION_PATH} will issue a session for " +
                    "\"${DevSessionIssuer.DEV_PRINCIPAL_SUB}\" to anyone who asks. " +
                    "Unset it for anything but local development."
            )
        }
    }
}
