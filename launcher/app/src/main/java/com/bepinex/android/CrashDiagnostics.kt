package com.bepinex.android

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Collects diagnostics for one recorded launch — the pending launch the
 * crash dialog is about (ADR-0022). The export is a curated report, not a
 * mirror of the per-game storage tree: which modpacks the launch used is
 * a line in `device.txt`, and the run's log is collected once, from the
 * runtime location.
 */
object CrashDiagnostics {
    data class PendingLaunch(
        val packageName: String,
        val startedAt: Long,
        /**
         * Modpacks the launch used, in launch order. Null when the pending
         * record was written before this field existed (not recorded);
         * an empty list means the launch was vanilla.
         */
        val modpackNames: List<String>? = null
    )

    private const val PREFS = "crash_diagnostics"
    private const val KEY_PACKAGE = "pending_package"
    private const val KEY_STARTED_AT = "pending_started_at"
    private const val KEY_MODPACKS = "pending_modpacks"
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L

    fun markLaunch(context: Context, packageName: String, modpackNames: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PACKAGE, packageName)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putString(KEY_MODPACKS, encodeModpackNames(modpackNames))
            .apply()
    }

    fun pending(context: Context): PendingLaunch? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = prefs.getString(KEY_PACKAGE, null) ?: return null
        // A record written before KEY_MODPACKS existed has no list at all;
        // that must stay distinguishable from a recorded vanilla launch.
        val modpackNames = if (prefs.contains(KEY_MODPACKS)) {
            decodeModpackNames(prefs.getString(KEY_MODPACKS, null))
        } else {
            null
        }
        return PendingLaunch(packageName, prefs.getLong(KEY_STARTED_AT, 0L), modpackNames)
    }

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_STARTED_AT)
            .remove(KEY_MODPACKS)
            .apply()
    }

    internal fun encodeModpackNames(names: List<String>): String =
        names.joinToString("\n")

    internal fun decodeModpackNames(encoded: String?): List<String> =
        if (encoded.isNullOrEmpty()) emptyList() else encoded.split("\n")

    internal fun formatModpackNames(names: List<String>?): String = when {
        names == null -> "(not recorded)"
        names.isEmpty() -> "(vanilla)"
        else -> names.joinToString(", ")
    }

    fun export(context: Context, pending: PendingLaunch): File {
        val packageName = pending.packageName
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(context.cacheDir, "bepinex_diagnostics_${packageName}_$stamp.zip")
        output.parentFile?.mkdirs()

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            addText(zip, "device.txt", buildString {
                appendLine("launcherPackage=${context.packageName}")
                appendLine("launcherVersion=${packageVersion(context, context.packageName)}")
                appendLine("targetPackage=$packageName")
                appendLine("targetVersion=${packageVersion(context, packageName)}")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine("model=${Build.MODEL}")
                appendLine("manufacturer=${Build.MANUFACTURER}")
                appendLine("launchedAt=${formatTimestamp(pending.startedAt)}")
                appendLine("activeModpacks=${formatModpackNames(pending.modpackNames)}")
                appendLine("time=${Date()}")
            })
            addCommand(zip, "logcat.txt", arrayOf("logcat", "-d", "-v", "threadtime"))
            addCommand(zip, "crash_logcat.txt", arrayOf("logcat", "-b", "crash", "-d", "-v", "threadtime"))

            addFileIfPresent(zip, "launcher/bepinex_launcher.log",
                File(context.getExternalFilesDir(null), "bepinex_launcher.log"))
            addFileIfPresent(zip, "launcher/logcat_previous.txt",
                File(context.getExternalFilesDir(null), "logcat.txt"))

            // Runtime logs only, at fixed paths. The per-pack and vanilla
            // snapshot copies under the game root are not collected: a run
            // has one combined log, attributed by the activeModpacks line
            // above, and the snapshots are copies of it (ADR-0022).
            addFileIfPresent(zip, "game/LogOutput.log",
                BepInExPaths.getLogFile(packageName))
            val logsDir = BepInExPaths.getLogsDir(packageName)
            if (logsDir.isDirectory) {
                logsDir.walkTopDown()
                    .filter { it.isFile && it.length() <= MAX_FILE_BYTES }
                    .forEach { file ->
                        val relative = file.relativeTo(logsDir).path.replace(File.separatorChar, '/')
                        addFileIfPresent(zip, "game/logs/$relative", file)
                    }
            }
        }
        return output
    }

    private fun packageVersion(context: Context, packageName: String): String =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")

    private fun formatTimestamp(epochMillis: Long): String =
        if (epochMillis <= 0L) "unknown"
        else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))

    private fun addCommand(zip: ZipOutputStream, name: String, command: Array<String>) {
        val process = try {
            ProcessBuilder(*command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            addText(zip, name, "Unable to run command: ${e.message}")
            return
        }
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }.also {
                process.waitFor(10, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            "Command failed: ${e.message}"
        } finally {
            process.destroy()
        }
        addText(zip, name, output)
    }

    private fun addFileIfPresent(zip: ZipOutputStream, name: String, file: File) {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return
        val entry = ZipEntry(name)
        // Keep the file's own modification time — for a log that is its
        // run time, which the export time would otherwise overwrite.
        entry.time = file.lastModified()
        zip.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
