package com.bepinex.android

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts BepInEx and .NET runtime from APK assets to per-game directories.
 *
 * Uses [BepInExPaths] for path resolution. Extraction is idempotent —
 * checks for marker files before extracting and skips if already complete.
 */
class FileExtractor(private val context: Context) {

    /**
     * Extract BepInEx.Android.zip for a specific game.
     * Skips if BepInEx.Core.dll marker already exists.
     */
    fun extractBepInExIfNeeded(packageName: String, onProgress: (String) -> Unit = {}) {
        val bepInExDir = BepInExPaths.getBepInExDir(packageName)
        val marker = File(bepInExDir, "core/BepInEx.Core.dll")

        if (marker.exists() && stampMatches(bepInExDir)) {
            BepInExLog.i("BepInEx already extracted for $packageName")
            return
        }

        // Only the framework-owned core is replaced. The game directory also
        // contains user plugins/config and generated interop; deleting the whole
        // tree on a launcher upgrade would destroy the production install.
        if (bepInExDir.exists()) {
            BepInExLog.i("BepInEx core stale (${launcherStamp()} != ${readStamp(bepInExDir)}) — re-extracting for $packageName")
            val coreDir = File(bepInExDir, "core")
            if (coreDir.exists() && !coreDir.deleteRecursively())
                throw IllegalStateException("Cannot replace BepInEx core: $coreDir")
            stampFile(bepInExDir).delete()
        }

        onProgress("Extracting BepInEx for $packageName...")
        BepInExLog.i("Extracting BepInEx.Android.zip → ${bepInExDir.absolutePath}")
        extractZip("BepInEx.Android.zip", bepInExDir)
        writeStamp(bepInExDir)
        BepInExLog.i("BepInEx extracted: ${bepInExDir.absolutePath}")
    }

    /**
     * Extract dotnet.zip for a specific game.
     * Skips if System.Private.CoreLib.dll marker already exists.
     */
    fun extractDotnetIfNeeded(packageName: String, onProgress: (String) -> Unit = {}) {
        val dotnetDir = BepInExPaths.getDotnetDir(packageName)
        val marker = File(dotnetDir, "System.Private.CoreLib.dll")

        if (marker.exists() && stampMatches(dotnetDir)) {
            BepInExLog.i("dotnet already extracted for $packageName")
            return
        }

        if (dotnetDir.exists()) {
            BepInExLog.i("dotnet dir stale (${launcherStamp()} != ${readStamp(dotnetDir)}) — re-extracting for $packageName")
            dotnetDir.deleteRecursively()
        }

        onProgress("Extracting .NET runtime for $packageName...")
        BepInExLog.i("Extracting dotnet.zip → ${dotnetDir.absolutePath}")
        extractZip("dotnet.zip", dotnetDir)
        writeStamp(dotnetDir)
        BepInExLog.i("dotnet extracted: ${dotnetDir.absolutePath}")
    }

    /**
     * Check if BepInEx and dotnet are both ready for a game.
     */
    fun isFrameworkReady(packageName: String): Boolean =
        BepInExPaths.isBepInExExtracted(packageName) &&
        BepInExPaths.isDotnetExtracted(packageName) &&
        stampMatches(BepInExPaths.getBepInExDir(packageName)) &&
        stampMatches(BepInExPaths.getDotnetDir(packageName))

    /** Package version revision pinned into extraction stamps. */
    private fun launcherStamp(): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)}:${pi.versionName}"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun stampFile(dir: File) = File(dir, ".extracted-version")

    private fun writeStamp(dir: File) {
        stampFile(dir).writeText(launcherStamp())
    }

    private fun readStamp(dir: File): String =
        try { stampFile(dir).readText().trim() } catch (e: Exception) { "<none>" }

    private fun stampMatches(dir: File): Boolean = readStamp(dir) == launcherStamp()

    /**
     * Extract a ZIP asset to a destination directory.
     * Handles Windows path separators (\ → /) for compatibility.
     */
    private fun extractZip(assetName: String, destDir: File) {
        if (!destDir.mkdirs() && !destDir.exists()) {
            val msg = "Cannot create dest dir: ${destDir.absolutePath} — check MANAGE_EXTERNAL_STORAGE permission"
            BepInExLog.e(msg)
            throw RuntimeException(msg)
        }
        var extractCount = 0
        context.assets.open(assetName).use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // Normalize path separators: Windows-created zips may use \ instead of /
                    val normalizedName = entry.name.replace('\\', '/')
                    val outFile = File(destDir, normalizedName)
                    if (entry.isDirectory || normalizedName.endsWith("/")) {
                        if (!outFile.mkdirs() && !outFile.exists()) {
                            BepInExLog.w("Cannot create dir: ${outFile.absolutePath}")
                        }
                    } else {
                        // Never overwrite a user's BepInEx.cfg during a core
                        // refresh; zip templates seed config only on first install.
                        if (assetName == "BepInEx.Android.zip" &&
                            normalizedName.startsWith("config/") && outFile.exists()) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        val parent = outFile.parentFile
                        if (parent != null && !parent.mkdirs() && !parent.exists()) {
                            val msg = "Cannot create parent dir: ${parent.absolutePath} — storage permission issue?"
                            BepInExLog.e(msg)
                            throw RuntimeException(msg)
                        }
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        extractCount++
                    }
                    entry = zis.nextEntry
                }
            }
        }
        BepInExLog.i("Extracted $extractCount files to ${destDir.absolutePath}")
    }
}
