package com.example.aiinterviewapp.utils

import com.example.aiinterviewapp.BuildConfig

object Constants {
    /**
     * SHERIF's backend, from `SHERIF_API_BASE_URL` in `local.properties`.
     *
     * This replaces the Phase 2 base URL of `generativelanguage.googleapis.com`.
     * The app no longer talks to Google at all: the Gemini key lives only in the
     * backend's environment, so a user who unpacks the APK holds no credential
     * (RULE 2, RULE 20).
     *
     * A release build must be `https`. A cleartext backend would put the
     * session token on the wire in a form anyone on the network can read. A
     * debug build may point at a local `http` backend, which is the only reason
     * `app/src/debug` carries a cleartext network security config; that config
     * is not part of a release build.
     *
     * Normalised on the way through, because Retrofit throws at dependency
     * injection time on a base URL whose path does not end in `/`, and a
     * missing slash is a typo rather than a decision.
     */
    val SHERIF_API_BASE_URL: String = SherifApiBaseUrl.normalize(BuildConfig.SHERIF_API_BASE_URL)

    /**
     * False when no real backend address was ever supplied and the reserved
     * `.invalid` placeholder is still compiled in.
     *
     * `.invalid` cannot resolve, so an unconfigured app cannot reach a
     * backend by accident. It fails, but the failure arrives as an
     * `UnknownHostException`, which the error mapper would otherwise report as
     * "the AI service is temporarily unavailable" -- true of the service and
     * useless to whoever has to fix it. This flag lets the mapper say what is
     * actually wrong.
     */
    val isApiConfigured: Boolean = SherifApiBaseUrl.isConfigured(SHERIF_API_BASE_URL)
}
