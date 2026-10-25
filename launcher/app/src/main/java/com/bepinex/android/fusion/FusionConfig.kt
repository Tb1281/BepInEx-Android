package com.bepinex.android.fusion

/**
 * Configuration passed to the native libfusion.so layer.
 *
 * Mirrors the FusionConfig struct in fusion.cpp. Written to disk by
 * [FusionConfigStore] and read by libmain's load() → fusion_stage_from_config_path().
 */
data class FusionConfig(
    /** Directory where the GAME's native libraries are located (Among Us APK lib/arm64-v8a) */
    val gameLibraryDirectory: String,

    /**
     * Explicit path for the game's `libunity.so`. This may be an APK entry path
     * (`<apk>!/lib/<abi>/libunity.so`) so the module keeps the path it has in a
     * normal game launch. Empty means `gameLibraryDirectory/libunity.so`.
     */
    val gameUnityLibraryPath: String = "",

    /** Directory where OUR native libraries are located (this APK's nativeLibraryDir) */
    val appLibraryDirectory: String,

    /** App-private data directory for staged files (libil2cpp.so, dotnet, copied Data) */
    val appDataDirectory: String,

    /** Root directory for BepInEx (contains core/, plugins/, config/) */
    val bepInExDirectory: String,

    /** Directory containing .NET CoreCLR runtime assemblies */
    val dotnetDirectory: String,

    /** Directory containing the game's Unity Data/ files (copied from APK assets) */
    val unityDataDirectory: String,

    /** Unity engine version string (e.g. "2022.3.62f3") */
    val unityVersion: String,

    /** If true, use the game's original libunity.so instead of downloading a patched one */
    val useOriginalLibUnity: Boolean = false,

    /** App-specific external storage directory (context.getExternalFilesDir(null)) */
    val externalFilesDirectory: String = ""
) {
    /**
     * Serialize to the config file format expected by native fusion.cpp parser.
     *
     * Format: one key=value per line
     */
    fun toConfigFile(): String = buildString {
        appendLine("gameLibraryDirectory=${gameLibraryDirectory}")
        appendLine("gameUnityLibraryPath=${gameUnityLibraryPath}")
        appendLine("appLibraryDirectory=${appLibraryDirectory}")
        appendLine("appDataDirectory=${appDataDirectory}")
        appendLine("bepInExDirectory=${bepInExDirectory}")
        appendLine("dotnetDirectory=${dotnetDirectory}")
        appendLine("unityDataDirectory=${unityDataDirectory}")
        appendLine("unityVersion=${unityVersion}")
        appendLine("useOriginalLibUnity=${useOriginalLibUnity}")
        appendLine("externalFilesDirectory=${externalFilesDirectory}")
    }
}
