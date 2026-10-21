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

/**
 * Launcher Settings are global (they belong to the launcher app itself, not
 * to any one game), so this destination carries no package name: it must be
 * reachable with no game installed or selected. Per-game maintenance still
 * shown on that screen targets the currently selected game — see
 * BepInExNavHost — until the per-game settings screen (#35) takes it over.
 */
@Serializable
data object Settings

@Serializable
data class LogViewer(val packageName: String, val modpackName: String)

@Serializable
data object About
