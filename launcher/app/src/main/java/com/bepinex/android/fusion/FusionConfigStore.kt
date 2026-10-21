package com.bepinex.android.fusion

import android.content.Context
import com.bepinex.android.BepInExLog
import java.io.File

/**
 * Writes [FusionConfig] to disk so native libmain.so can read it.
 *
 * The config is written to the hosted game's per-game internal files root:
 *   {filesDir}/{gamePackage}/gamedata/bootstrap/active.cfg
 *
 * libmain's load() runs with the hosted game's context, whose filesDir is
 * redirected per game by [PerGameStorage]; resolve_staged_config_path()
 * therefore resolves {per-game filesDir}/bootstrap/active.cfg. Writing to the
 * launcher's own {filesDir}/bootstrap instead leaves the staged config where
 * the native side never looks, and the boot fails with "Failed to read
 * config".
 */
object FusionConfigStore {

    private const val CONFIG_DIR = "bootstrap"
    private const val CONFIG_FILE = "active.cfg"

    /**
     * Write the fusion config to the staged location for [gamePackage].
     *
     * @return The written config file (for logging/debugging)
     */
    fun write(context: Context, gamePackage: String, config: FusionConfig): File {
        val dir = File(PerGameStorage.filesDir(context, gamePackage), CONFIG_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Failed to create config dir: ${dir.absolutePath}")
        }

        val file = File(dir, CONFIG_FILE)
        val content = config.toConfigFile()

        file.writeText(content)

        BepInExLog.i("FusionConfigStore: written to ${file.absolutePath}")
        BepInExLog.i("  content: ${content.replace("\n", " | ")}")

        return file
    }

    /**
     * Get the expected config file path (for checking if it exists).
     */
    fun getConfigFile(context: Context, gamePackage: String): File =
        File(File(PerGameStorage.filesDir(context, gamePackage), CONFIG_DIR), CONFIG_FILE)
}
