package com.example.aiinterviewapp.utils

import com.example.aiinterviewapp.BuildConfig

/**
 * Whether this build may offer development sign-in.
 *
 * The answer is a compile-time constant, and that is the whole safety argument:
 * in a release build [buildEnabled] is the literal `false`, so every `if` on it
 * is folded away by the compiler and R8 removes the code behind it. The
 * development entry does not merely hide itself in release, it is not there.
 *
 * The flag is read from a constructor parameter rather than from [BuildConfig]
 * inside the logic so the rules below can be asserted in a unit test. Reading
 * `BuildConfig` directly would make "release cannot enable this" untestable
 * without building a release APK, which is precisely the claim worth testing.
 * [fromBuildConfig] is the one place the real value enters.
 */
data class DevAuthPolicy(
    /** The build's own `DEV_AUTH_ENABLED`. True only for debug. */
    val buildEnabled: Boolean,
    /** A real backend address is configured, so a session can be requested. */
    val apiConfigured: Boolean
) {

    /**
     * True when the development entry may be shown and used.
     *
     * [apiConfigured] is required as well as [buildEnabled]: a debug build
     * pointed at the `.invalid` placeholder could only fail, and offering a
     * button that cannot work is worse than not offering it.
     */
    val isAvailable: Boolean
        get() = buildEnabled && apiConfigured

    /**
     * The user id a development session is expected to carry.
     *
     * Read from the backend's response rather than assumed, so local data is
     * scoped to whatever the server actually issued.
     */
    fun isDevelopmentUser(userId: String?): Boolean =
        userId != null && userId.startsWith(DEV_USER_ID_PREFIX)

    companion object {
        /**
         * The reserved namespace for a development identity.
         *
         * Mirrors the backend's `DevSessionIssuer.DEV_PRINCIPAL_SUB`. Kept as a
         * constant rather than re-derived so a mismatch is a visible name
         * rather than a substring that quietly stops matching.
         */
        const val DEV_USER_ID_PREFIX = "dev:"

        /** The one identity development sign-in can produce. */
        const val DEV_USER_ID = "dev:local"

        /** The production policy. Unreachable, and a constant, in release. */
        val DISABLED = DevAuthPolicy(buildEnabled = false, apiConfigured = false)

        /** The real value for this build. */
        fun fromBuildConfig(
            buildEnabled: Boolean = BuildConfig.DEV_AUTH_ENABLED,
            apiConfigured: Boolean = Constants.isApiConfigured
        ): DevAuthPolicy = DevAuthPolicy(buildEnabled = buildEnabled, apiConfigured = apiConfigured)
    }
}
