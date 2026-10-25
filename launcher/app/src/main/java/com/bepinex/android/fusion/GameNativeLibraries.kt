package com.bepinex.android.fusion

import android.content.Context
import android.os.Build
import com.bepinex.android.BepInExLog
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Resolves the game's native libraries.
 *
 * Most APKs extract their native libraries into `ApplicationInfo.nativeLibraryDir`.
 * Apps installed with ABI splits (`extractNativeLibs=false`) keep the libraries
 * inside `split_config.<abi>.apk` and leave `nativeLibraryDir` empty. Fusion loads
 * `libunity.so` and `libil2cpp.so` by absolute path, so an empty directory makes
 * every load fail and the game never reaches il2cpp.
 *
 * For split installations the required libraries are extracted once into the
 * host's per-game data directory. A version stamp makes the extraction a no-op
 * until the game is updated. The extracted directory is passed to the integrity
 * shield as an allowed prefix so a protected app still sees its own libraries.
 *
 * `libunity.so` is special: integrity code inside it can inspect its own mapped
 * path, so it is loaded from the APK entry (`<apk>!/lib/<abi>/libunity.so`)
 * whenever the entry is stored uncompressed. That keeps the module path
 * identical to a normal game launch while the extracted copy remains available
 * for games that compress their libraries.
 */
object GameNativeLibraries {
    private const val STAMP_FILE = ".extracted-version"

    /** Libraries Fusion loads by absolute path. */
    private val REQUIRED = listOf("libunity.so", "libil2cpp.so")

    /**
     * Result of resolving the game's native libraries.
     *
     * @property directory directory holding the extracted libraries; used for
     *     il2cpp patching and for the integrity shield's allow list.
     * @property unityLibraryPath preferred path for `libunity.so`, which may be
     *     an APK entry path.
     */
    data class Layout(
        val directory: String,
        val unityLibraryPath: String,
    )

    /**
     * Resolves the game's native libraries, extracting them from split APKs
     * when necessary.
     *
     * @param gameContext a context for the game package
     * @param appDataDir the host's per-game internal data directory
     */
    fun ensureAvailable(gameContext: Context, appDataDir: File): Layout {
        val info = gameContext.applicationInfo
        val direct = info.nativeLibraryDir
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        if (direct.isNotBlank() && REQUIRED.all { File(direct, it).isFile }) {
            return Layout(
                directory = direct,
                unityLibraryPath = storedApkEntry(info, abi, "libunity.so")
                    ?: File(direct, "libunity.so").absolutePath,
            )
        }

        val dest = File(appDataDir, "game-libs/$abi")
        val version = packageVersion(gameContext)
        val stamp = File(dest, STAMP_FILE)
        val alreadyExtracted = stamp.isFile &&
            runCatching { stamp.readText().trim() }.getOrNull() == version &&
            REQUIRED.all { File(dest, it).isFile }

        val unityLibraryPath = storedApkEntry(info, abi, "libunity.so")
            ?: File(dest, "libunity.so").absolutePath

        if (alreadyExtracted) {
            BepInExLog.i("GameNativeLibraries: using extracted split libraries at ${dest.absolutePath}")
            BepInExLog.i("GameNativeLibraries: libunity path ${unityLibraryPath}")
            return Layout(dest.absolutePath, unityLibraryPath)
        }

        dest.mkdirs()
        dest.listFiles()?.forEach { file ->
            if (file.name != STAMP_FILE) file.delete()
        }

        var extracted = 0
        for (apk in apkPaths(info)) {
            try {
                ZipFile(apk).use { zip ->
                    for (name in REQUIRED) {
                        val entryName = "lib/$abi/$name"
                        val entry = zip.getEntry(entryName) ?: continue
                        val target = File(dest, name)
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output, 1 shl 20)
                            }
                        }
                        extracted++
                        BepInExLog.i(
                            "GameNativeLibraries: extracted $entryName " +
                                "(${entry.size} bytes) -> ${target.absolutePath}"
                        )
                    }
                }
            } catch (t: Throwable) {
                BepInExLog.w("GameNativeLibraries: cannot inspect $apk: ${t.message}")
            }
        }

        if (extracted == 0) {
            BepInExLog.w(
                "GameNativeLibraries: no split libraries found (abi=$abi, " +
                    "nativeLibraryDir=$direct)"
            )
            return Layout(direct, unityLibraryPath)
        }

        runCatching { stamp.writeText(version) }
        BepInExLog.i("GameNativeLibraries: libunity path ${unityLibraryPath}")
        return Layout(dest.absolutePath, unityLibraryPath)
    }

    /**
     * Returns `<apk>!/lib/<abi>/<name>` when the library is stored uncompressed
     * in one of the package's APKs, so bionic can map it directly. Compressed
     * entries must be extracted first; they cannot be loaded from the APK.
     */
    private fun storedApkEntry(
        info: android.content.pm.ApplicationInfo,
        abi: String,
        name: String,
    ): String? {
        for (apk in apkPaths(info)) {
            try {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/$abi/$name") ?: return@use
                    if (entry.method == ZipEntry.STORED) {
                        return "$apk!/lib/$abi/$name"
                    }
                }
            } catch (t: Throwable) {
                BepInExLog.w("GameNativeLibraries: cannot inspect $apk: ${t.message}")
            }
        }
        return null
    }

    private fun packageVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
        "$code:${info.versionName}"
    } catch (t: Throwable) {
        "unknown"
    }

    private fun apkPaths(info: android.content.pm.ApplicationInfo): List<String> {
        val paths = ArrayList<String>()
        info.sourceDir?.let { paths.add(it) }
        info.splitSourceDirs?.let { paths.addAll(it) }
        return paths.distinct()
    }
}
