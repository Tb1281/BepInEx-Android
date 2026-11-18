package com.bepinex.android.fusion

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.bepinex.android.BepInExLog
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Resolves the game's native libraries for both monolithic and split installs.
 *
 * When `ApplicationInfo.nativeLibraryDir` is populated, the game's libraries are
 * already extracted and can be used directly. Split installs
 * (`extractNativeLibs=false`) usually keep them inside `split_config.<abi>.apk`
 * and can leave that directory empty (some still populate it). Fusion loads
 * `libunity.so` and `libil2cpp.so` by absolute path, so an empty directory makes
 * every load fail and the game never reaches il2cpp.
 *
 * For split installs every `.so` under `lib/<abi>/` is staged into the host's
 * per-game data directory, including companion libraries that are `DT_NEEDED`
 * dependencies of the game libraries or are looked up through
 * `nativeLibraryDir`. A version stamp makes re-copying a no-op until the game
 * is updated; the APKs are still opened once per launch to look for a mappable
 * `libunity.so`.
 *
 * `libunity.so` is special: when stored uncompressed, an APK entry path
 * (`<apk>!/lib/<abi>/libunity.so`) can be mapped directly by bionic's linker,
 * matching a normal game launch; compressed entries cannot be mapped and fall
 * back to the staged copy.
 */
object GameNativeLibraries {
    private const val STAMP_FILE = ".extracted-version"
    private const val TEMP_SUFFIX = ".tmp"
    private const val COPY_BUFFER_SIZE = 1 shl 20

    /** Libraries Fusion loads by absolute path. */
    private val REQUIRED = listOf("libunity.so", "libil2cpp.so")

    /**
     * Result of resolving the game's native libraries.
     *
     * @property directory directory holding the game libraries; used for
     *     il2cpp patching, `libmain.so`, and extra native libraries.
     * @property unityLibraryPath preferred path for `libunity.so`, which may be
     *     an APK entry path.
     */
    data class Layout(
        val directory: String,
        val unityLibraryPath: String,
    )

    /**
     * Resolves the game's native libraries, staging them from split APKs when
     * necessary.
     *
     * @param gameContext a context for the game package
     * @param appDataDir the host's per-game internal data directory
     */
    fun ensureAvailable(gameContext: Context, appDataDir: File): Layout {
        val info = gameContext.applicationInfo
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return resolveAndStage(
            nativeLibraryDir = info.nativeLibraryDir.orEmpty(),
            apkPaths = apkPaths(info),
            abi = abi,
            version = PackageVersion.stamp(gameContext),
            destination = File(appDataDir, "game-libs/$abi"),
            log = BepInExLog::i,
        )
    }

    /**
     * Pure-JVM resolution and staging core, split out from [ensureAvailable] so
     * the split-APK behaviour can be tested without an Android runtime.
     *
     * @param nativeLibraryDir `ApplicationInfo.nativeLibraryDir`; blank for
     *     split installs
     * @param apkPaths base APK followed by any split APKs
     * @param abi ABI directory to read from the APKs (`arm64-v8a`)
     * @param version package version stamp used to invalidate staged files
     * @param destination directory the split libraries are staged into
     * @param log informational/warning sink
     */
    internal fun resolveAndStage(
        nativeLibraryDir: String,
        apkPaths: List<String>,
        abi: String,
        version: String,
        destination: File,
        log: (String) -> Unit = {},
    ): Layout {
        val direct = nativeLibraryDir.trim()

        // Already extracted by the platform: use it as-is and never inspect the
        // APKs, so monolithic installs keep their nativeLibraryDir load path.
        if (direct.isNotEmpty() && REQUIRED.all { File(direct, it).isFile }) {
            val unityPath = File(direct, "libunity.so").absolutePath
            log("GameNativeLibraries: using nativeLibraryDir $direct")
            log("GameNativeLibraries: libunity path $unityPath")
            return Layout(direct, unityPath)
        }

        val storedUnity = storedApkEntry(apkPaths, abi, "libunity.so", log)

        if (isStaged(destination, version)) {
            log("GameNativeLibraries: using staged split libraries at ${destination.absolutePath}")
            return stagedLayout(destination, direct, storedUnity, log)
        }

        clearDestination(destination)
        val extracted = stageSplitLibraries(apkPaths, abi, destination, log)
        writeStampIfComplete(destination, version, abi, direct, log)

        if (extracted == 0) {
            log(
                "GameNativeLibraries: no split libraries found " +
                    "(abi=$abi, nativeLibraryDir=$direct)"
            )
        }

        return stagedLayout(destination, direct, storedUnity, log)
    }

    private fun writeStampIfComplete(
        destination: File,
        version: String,
        abi: String,
        nativeLibraryDir: String,
        log: (String) -> Unit,
    ) {
        if (REQUIRED.all { File(destination, it).isFile }) {
            runCatching { File(destination, STAMP_FILE).writeText(version) }
            return
        }
        log(
            "GameNativeLibraries: required libraries missing after staging " +
                "(abi=$abi, nativeLibraryDir=$nativeLibraryDir)"
        )
    }

    /** True when [destination] already holds this [version]'s staged libraries. */
    private fun isStaged(destination: File, version: String): Boolean {
        val stamp = File(destination, STAMP_FILE)
        return stamp.isFile &&
            runCatching { stamp.readText().trim() }.getOrNull() == version &&
            REQUIRED.all { File(destination, it).isFile }
    }

    private fun clearDestination(destination: File) {
        destination.mkdirs()
        destination.listFiles()?.forEach { file ->
            if (file.name != STAMP_FILE) file.delete()
        }
    }

    private fun stagedLayout(
        destination: File,
        nativeLibraryDir: String,
        storedUnity: String?,
        log: (String) -> Unit,
    ): Layout {
        val unityPath = computeUnityPath(
            storedUnity = storedUnity,
            extractedUnity = File(destination, "libunity.so"),
            nativeLibraryDir = nativeLibraryDir,
        )
        log("GameNativeLibraries: libunity path $unityPath")
        return Layout(destination.absolutePath, unityPath)
    }

    /**
     * Prefers a mappable APK entry, then the staged copy, and only then the
     * platform's `nativeLibraryDir` (empty for split installs).
     */
    private fun computeUnityPath(
        storedUnity: String?,
        extractedUnity: File,
        nativeLibraryDir: String,
    ): String = storedUnity
        ?: if (extractedUnity.isFile) extractedUnity.absolutePath
        else File(nativeLibraryDir, "libunity.so").absolutePath

    /**
     * Copies every `.so` library under `lib/<abi>/` from [apkPaths] into [destination].
     *
     * @return the number of entries extracted
     */
    private fun stageSplitLibraries(
        apkPaths: List<String>,
        abi: String,
        destination: File,
        log: (String) -> Unit,
    ): Int {
        var extracted = 0
        for (apk in apkPaths.distinct()) {
            try {
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name
                        val prefix = "lib/$abi/"
                        if (!name.startsWith(prefix) || !name.endsWith(".so")) continue

                        val fileName = name.substring(prefix.length)
                        if (fileName.isEmpty() || fileName.contains('/')) continue

                        val target = File(destination, fileName)
                        try {
                            stageEntry(zip, entry, target)
                        } catch (e: Exception) {
                            log("GameNativeLibraries: failed to stage $name: ${e.message}")
                            continue
                        }
                        extracted++
                        log(
                            "GameNativeLibraries: extracted $name " +
                                "(${entry.size} bytes) -> ${target.absolutePath}"
                        )
                    }
                }
            } catch (e: Exception) {
                log("GameNativeLibraries: cannot inspect $apk: ${e.message}")
            }
        }
        return extracted
    }

    /**
     * Writes [entry] to [target] through a sibling `.tmp` file that is renamed
     * into place, so an interrupted extraction never corrupts a staged library.
     */
    private fun stageEntry(zip: ZipFile, entry: ZipEntry, target: File) {
        val temporary = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            zip.getInputStream(entry).use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            if (!temporary.renameTo(target)) {
                // Renames are atomic within a directory; fall back to a copy on
                // filesystems that refuse them rather than losing the library.
                temporary.copyTo(target, overwrite = true)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /**
     * Returns `<apk>!/lib/<abi>/<name>` when the library is stored uncompressed
     * in one of the package's APKs, so bionic can map it directly. Compressed
     * entries must be staged first; they cannot be loaded from the APK.
     */
    private fun storedApkEntry(
        apkPaths: List<String>,
        abi: String,
        name: String,
        log: (String) -> Unit,
    ): String? {
        for (apk in apkPaths.distinct()) {
            try {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/$abi/$name") ?: return@use
                    if (entry.method == ZipEntry.STORED) {
                        return "$apk!/lib/$abi/$name"
                    }
                }
            } catch (e: Exception) {
                log("GameNativeLibraries: cannot inspect $apk: ${e.message}")
            }
        }
        return null
    }

    private fun apkPaths(info: ApplicationInfo): List<String> {
        val paths = ArrayList<String>()
        info.sourceDir?.let { paths.add(it) }
        info.splitSourceDirs?.let { paths.addAll(it) }
        return paths.distinct()
    }
}
