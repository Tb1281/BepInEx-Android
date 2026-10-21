package com.bepinex.android

import android.content.Context
import com.bepinex.android.fusion.GameResourceContext
import java.io.File

/**
 * Resolves BepInEx paths for any game package.
 *
 * External: {externalFilesDir}/{gamePackage}/BepInEx/
 * Internal: {filesDir}/{gamePackage}/   (dotnet, Data_copy, gamedata)
 *
 * Per-game directories sit directly under the app storage roots - there is
 * no intermediate namespace folder. Package names cannot collide with the
 * engine/runtime entries that share the external root (Unity/, il2cpp/,
 * logs, tombstones), and the internal root already used this flat layout.
 *
 * The launcher's applicationId and filesDir are read from the process context
 * installed by [install]. Every process that touches these paths must install
 * first: [MainActivity] and [LogOverlayService] run in the main process,
 * [BootstrapActivity] in `:game`, and a static is per-process, so installing
 * from one does not cover the others.
 */
object BepInExPaths {

    @Volatile
    private var appContext: Context? = null

    /**
     * Records the process context used for path resolution. Idempotent and
     * cheap, so calling it from every entry point is harmless.
     *
     * The context is unwrapped to the root ContextImpl on purpose: these are
     * the launcher's own bookkeeping paths and must resolve identically in
     * every process. In the `:game` process the Application's base is wrapped
     * in a GameResourceContext so the *game's* storage lands per game;
     * resolving launcher paths through that wrapper would re-root them under
     * `<game>/gamedata/<game>/` — extraction, modpack apply and the fusion
     * config's BepInEx root would then disagree with the launcher process
     * about where anything lives. Per-game redirection belongs to the game's
     * view of storage, not to the launcher's path bookkeeping.
     */
    fun install(context: Context) {
        appContext = GameResourceContext.unwrapToRootContext(context.applicationContext)
    }

    /** The launcher package name, which is what distinguishes test from production. */
    private fun launcherApplicationId(): String =
        requireContext().packageName

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException(
            "BepInExPaths.install(context) must run before any path is resolved " +
                "(call it from Activity/Service onCreate)"
        )

    /**
     * BepInEx root directory for a game package.
     * Uses plain Android context APIs (Context.getExternalFilesDir), so the
     * default is app-specific external storage with no MANAGE_EXTERNAL_STORAGE.
     */
    fun getGameRootDir(packageName: String): File {
        val baseDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        return File(baseDir, packageName)
    }

    fun getBepInExDir(packageName: String): File =
        File(getGameRootDir(packageName), "BepInEx")

    fun getPluginsDir(packageName: String): File =
        File(getBepInExDir(packageName), "plugins")

    fun getCoreDir(packageName: String): File =
        File(getBepInExDir(packageName), "core")

    fun getConfigDir(packageName: String): File =
        File(getBepInExDir(packageName), "config")

    fun getInteropDir(packageName: String): File =
        File(getBepInExDir(packageName), "interop")

    fun getLogFile(packageName: String): File =
        File(getBepInExDir(packageName), "LogOutput.log")

    fun getLogsDir(packageName: String): File =
        File(getBepInExDir(packageName), "logs")

    fun getUnityLibsDir(packageName: String): File =
        File(getBepInExDir(packageName), "unity-libs")

    fun getVanillaStateDir(packageName: String): File =
        File(getGameRootDir(packageName), "vanilla")

    fun getModpackDir(packageName: String, modpackName: String): File =
        File(getGameRootDir(packageName), "modpacks/$modpackName")

    fun getModpackConfigDir(packageName: String, modpackName: String): File =
        File(getModpackDir(packageName, modpackName), "config")

    fun getModpackLogsDir(packageName: String, modpackName: String): File =
        File(getModpackDir(packageName, modpackName), "logs")

    fun getModpackLogFile(packageName: String, modpackName: String?): File {
        if (modpackName.isNullOrEmpty()) {
            return File(getVanillaStateDir(packageName), "logs/LogOutput.log")
        }
        return File(getModpackLogsDir(packageName, modpackName), "LogOutput.log")
    }

    /** Internal app-private data directory for a game: .NET runtime, copied
     *  resources and the Unity library cache. Same layout for test and production. */
    fun getAppDataDir(packageName: String): File =
        File(requireContext().filesDir, packageName)

    fun getDotnetDir(packageName: String): File =
        File(getAppDataDir(packageName), "dotnet")

    fun getCopiedDataDir(packageName: String): File =
        File(getAppDataDir(packageName), "Data_copy")

    /** Check if BepInEx has been extracted for a game */
    fun isBepInExExtracted(packageName: String): Boolean =
        File(getCoreDir(packageName), "BepInEx.Core.dll").exists()

    /** Check if dotnet runtime has been extracted for a game */
    fun isDotnetExtracted(packageName: String): Boolean =
        File(getDotnetDir(packageName), "System.Private.CoreLib.dll").exists()
}