package com.bepinex.android.fusion

import android.content.Context
import java.io.File

/**
 * Per-game storage roots for hosted games.
 *
 * The game process runs under the launcher's identity, so without redirection
 * every game resolves the same storage paths (the launcher's files root) and
 * game-written files pile up there shared across all games - e.g. The Tower's
 * `playerInfo.dat`, the `Unity/` engine folder and crash dumps all landed in
 * the launcher's external files root regardless of the target game.
 *
 * These helpers scope the storage a hosted game can see to that game:
 *  - external files: `<externalFiles>/BepInEx_Android/<game>/gamedata/`
 *    (inside the game's existing BepInExPaths root, next to BepInEx/modpacks)
 *  - internal files: `<filesDir>/<game>/gamedata/`
 *    (inside the game's existing internal dir, next to dotnet/Data_copy)
 *  - caches: `<cacheDir>/<game>/`, `<externalCacheDir>/<game>/`
 */
object PerGameStorage {

    @JvmStatic
    fun filesDir(storage: Context, gamePackage: String): File =
        File(storage.filesDir, "$gamePackage/gamedata").apply { mkdirs() }

    @JvmStatic
    fun cacheDir(storage: Context, gamePackage: String): File =
        File(storage.cacheDir, gamePackage).apply { mkdirs() }

    @JvmStatic
    fun externalFilesDir(storage: Context, gamePackage: String, type: String?): File? {
        val base = storage.getExternalFilesDir(null) ?: return null
        val root = File(base, "BepInEx_Android/$gamePackage/gamedata")
        val dir = if (type.isNullOrEmpty()) root else File(root, type)
        dir.mkdirs()
        return dir
    }

    @JvmStatic
    fun externalCacheDir(storage: Context, gamePackage: String): File? {
        val base = storage.externalCacheDir ?: return null
        return File(base, gamePackage).apply { mkdirs() }
    }
}
