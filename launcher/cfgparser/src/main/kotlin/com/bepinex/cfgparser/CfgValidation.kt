package com.bepinex.cfgparser

/**
 * The outcome of validating (or applying) a candidate value for a cfg entry.
 * [Invalid.reason] is written for the Mod User, not the developer: it says
 * what was wrong and what values would be accepted.
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}
