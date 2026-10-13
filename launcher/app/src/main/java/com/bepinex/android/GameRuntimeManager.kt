package com.bepinex.android

import java.io.File

/**
 * Runtime path resolver using FusionCore-compatible storage layout.
 *
 * Delegates to [BepInExPaths] for all path resolution, which reads the process
 * context installed by `BepInExPaths.install`.
 */
class GameRuntimeManager {

    fun getBepInExDir(packageName: String): File =
        BepInExPaths.getBepInExDir(packageName)

    fun getBepInExCoreDir(packageName: String): File =
        BepInExPaths.getCoreDir(packageName)

    fun getBepInExPluginsDir(packageName: String): File =
        BepInExPaths.getPluginsDir(packageName)

    fun getDotnetDir(packageName: String): File =
        BepInExPaths.getDotnetDir(packageName)

    fun getGameDataDir(packageName: String): File =
        BepInExPaths.getAppDataDir(packageName)

    fun getGameRootDir(packageName: String): File =
        BepInExPaths.getGameRootDir(packageName)
}
