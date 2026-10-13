package com.bepinex.android

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Resolves BepInEx paths for any game package.
 *
 * Production: /storage/emulated/0/BepInEx_Android/{gamePackage}/BepInEx/
 * Test APKs:  {filesDir}/BepInEx_Android/{gamePackage}/BepInEx/
 * Internal:   {filesDir}/{gamePackage}/   (dotnet, Data_copy, libunity cache)
 *
 * A test launcher (`applicationId` ending in `.test`) must never overwrite the
 * production install's core, plugins or configs, so it resolves into the
 * app-private internal storage instead of the shared public directory. That
 * also removes its dependency on MANAGE_EXTERNAL_STORAGE. Production builds
 * keep the public layout unchanged, because that is where users manage their
 * mods with a file manager.
 *
 * The launcher's applicationId and filesDir are read from the process context
 * installed by [install]. Every process that touches these paths must install
 * first: [MainActivity] and [LogOverlayService] run in the main process,
 * [BootstrapActivity] in `:game`, and a static is per-process, so installing
 * from one does not cover the others.
 */
object BepInExPaths {

    /** Shared root of the public layout, and the subdirectory test builds use
     *  inside internal storage. */
    private const val ROOT_DIR = "BepInEx_Android"

    /** Suffix that distinguishes a test launcher from the production one. */
    private const val TEST_SUFFIX = ".test"

    @Volatile
    private var appContext: Context? = null

    /**
     * Records the process context used for path resolution. Idempotent and
     * cheap, so calling it from every entry point is harmless.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
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
        return File(baseDir, "$ROOT_DIR/$packageName")
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