package com.mainnetwallet.polybot.core

import kotlinx.serialization.json.Json

/**
 * Tolerant JSON: unknown keys are ignored and `null` / unknown values fall back to the model default,
 * so a backend that gains or loses a field never crashes the app.
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}
