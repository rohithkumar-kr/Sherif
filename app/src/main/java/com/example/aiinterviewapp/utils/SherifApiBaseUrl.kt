package com.example.aiinterviewapp.utils

/**
 * The two rules the rest of the app relies on about the backend's address.
 *
 * They live here, apart from [Constants], because they are pure functions of a
 * string and that is what makes them testable. [Constants] can only be read
 * once a `BuildConfig` exists, and a unit test has no way to hand it a
 * different value.
 */
object SherifApiBaseUrl {

    /**
     * The host that means "nobody ever configured this".
     *
     * `.invalid` is reserved by RFC 2606 precisely so that it can never resolve,
     * which is what makes it a safe placeholder: an unconfigured app fails
     * loudly instead of quietly reaching somebody else's server.
     */
    const val UNCONFIGURED_HOST_MARKER = "api.sherif.invalid"

    /**
     * Retrofit requires the path of a base URL to end in `/`, and throws during
     * dependency injection when it does not, which takes the app down before it
     * draws anything. A missing trailing slash is the single most likely typo
     * in a hand-written address, so it is corrected here rather than left as a
     * startup crash.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /** False while the reserved placeholder is still in the configured address. */
    fun isConfigured(baseUrl: String): Boolean = !baseUrl.contains(UNCONFIGURED_HOST_MARKER)
}
