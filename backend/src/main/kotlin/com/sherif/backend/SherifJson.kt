package com.sherif.backend

import kotlinx.serialization.json.Json

/**
 * One parser configuration for the whole backend.
 *
 * `ignoreUnknownKeys` is on so the gateway can re-serialise a validated
 * request without exploding on a field it does not model; validation, not
 * parsing, is what rejects unexpected input.
 */
val SherifJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = true
}
