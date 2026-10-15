package com.bepinex.android.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations.
 *
 * Arguments are encoded by Navigation itself, so modpack names containing `/`,
 * `%`, or spaces can no longer break route parsing the way manual string
 * interpolation did.
 */
@Serializable
data object Games

@Serializable
data class Modpacks(val packageName: String)

@Serializable
data class ModpackDetail(val packageName: String, val modpackName: String)

@Serializable
data class Settings(val packageName: String)

@Serializable
data class LogViewer(val packageName: String, val modpackName: String)

@Serializable
data object About
