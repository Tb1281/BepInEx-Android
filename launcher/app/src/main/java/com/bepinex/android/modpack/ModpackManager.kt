package com.bepinex.android.modpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.bepinex.android.BepInExLog
import com.bepinex.android.BepInExPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Metadata for a modpack.
 */
data class ModpackMeta(
    val name: String,
    val packageName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val modCount: Int = 0
)

/**
 * Progress details during modpack export.
 */
data class ModpackExportProgress(
    val phase: String,
    val currentFile: String? = null,
    val completedFiles: Long = 0,
    val totalFiles: Long = 0,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0
) {
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (completedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            totalFiles > 0 -> (completedFiles.toDouble() / totalFiles).coerceIn(0.0, 1.0).toFloat()
            else -> 0f
        }
}

/**
 * Manages modpack CRUD operations, activation/isolation, export, and import.
 */
class ModpackManager {

    companion object {
        const val MODPACK_EXTENSION = "rhp"
        const val MODPACK_MIME_TYPE = "application/octet-stream"

        private val SUPPORTED_MODPACK_EXTENSIONS = setOf("rhp", "zip")

        fun isModpackFileName(fileName: String?): Boolean =
            fileName?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_MODPACK_EXTENSIONS

        fun isModFileName(fileName: String?): Boolean =
            fileName?.substringAfterLast('.', "")?.equals("dll", ignoreCase = true) == true
    }

    fun normalizeModpackName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()

    private fun getModpacksDir(packageName: String): File =
        File(BepInExPaths.getGameRootDir(packageName), "modpacks")

    private fun getModpackDir(packageName: String, name: String): File =
        BepInExPaths.getModpackDir(packageName, name)

    private fun getModpackPluginsDir(packageName: String, name: String): File =
        File(getModpackDir(packageName, name), "plugins")

    private fun getModpackConfigDir(packageName: String, name: String): File =
        BepInExPaths.getModpackConfigDir(packageName, name)

    private fun getModpackLogsDir(packageName: String, name: String): File =
        BepInExPaths.getModpackLogsDir(packageName, name)

    private fun getMetaFile(packageName: String, name: String): File =
        File(getModpackDir(packageName, name), "modpack.json")

    // CRUD

    fun listModpacks(packageName: String): List<ModpackMeta> {
        val dir = getModpacksDir(packageName)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readMeta(packageName, it.name) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun createModpack(packageName: String, name: String): ModpackMeta? {
        val safeName = normalizeModpackName(name)
        if (safeName.isEmpty()) return null

        val modpackDir = getModpackDir(packageName, safeName)
        if (modpackDir.exists()) return null

        return try {
            modpackDir.mkdirs()
            getModpackPluginsDir(packageName, safeName).mkdirs()
            getModpackConfigDir(packageName, safeName).mkdirs()
            getModpackLogsDir(packageName, safeName).mkdirs()

            val meta = ModpackMeta(name = safeName, packageName = packageName)
            writeMeta(meta)
            BepInExLog.i("Created modpack: $safeName")
            meta
        } catch (e: Exception) {
            BepInExLog.e("Failed to create modpack: $safeName", e)
            null
        }
    }

    fun deleteModpack(packageName: String, name: String): Boolean {
        val dir = getModpackDir(packageName, name)
        return if (dir.exists()) {
            dir.deleteRecursively().also {
                BepInExLog.i("Deleted modpack: $name")
            }
        } else false
    }

    fun renameModpack(packageName: String, oldName: String, newName: String): Boolean {
        val safeNewName = normalizeModpackName(newName)
        if (safeNewName.isEmpty()) return false
        if (safeNewName == oldName) return true

        val oldDir = getModpackDir(packageName, oldName)
        val newDir = getModpackDir(packageName, safeNewName)
        if (!oldDir.exists() || newDir.exists()) return false

        if (!oldDir.renameTo(newDir)) return false

        try {
            val meta = readMeta(packageName, safeNewName)
                ?: ModpackMeta(name = safeNewName, packageName = packageName)
            writeMeta(meta.copy(name = safeNewName, packageName = packageName))
        } catch (e: Exception) {
            BepInExLog.e("Renamed modpack but failed to update metadata: $safeNewName", e)
        }

        BepInExLog.i("Renamed modpack: $oldName -> $safeNewName")
        return true
    }

    // Mod management

    private fun resolveFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun syncModpackMeta(packageName: String, modpackName: String) {
        val current = readMeta(packageName, modpackName) ?: return
        val real = getModCount(packageName, modpackName)
        if (current.modCount != real) {
            writeMeta(current.copy(modCount = real))
        }
    }

    fun listMods(packageName: String, modpackName: String): List<File> {
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        return if (pluginsDir.isDirectory) {
            pluginsDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
                .toList()
        } else {
            emptyList()
        }
    }

    fun listConfigs(packageName: String, modpackName: String): List<File> {
        val configDir = getModpackConfigDir(packageName, modpackName)
        return configDir.listFiles()?.filter { it.isFile && it.extension == "cfg" } ?: emptyList()
    }

    fun addMod(packageName: String, modpackName: String, sourceFile: File): File? {
        if (!isModFileName(sourceFile.name)) {
            BepInExLog.w("Rejected mod with unsupported extension: ${sourceFile.name}")
            return null
        }
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        pluginsDir.mkdirs()
        val dest = File(pluginsDir, sourceFile.name)
        return try {
            sourceFile.copyTo(dest, overwrite = true).also {
                syncModpackMeta(packageName, modpackName)
                BepInExLog.i("Added mod: ${sourceFile.name} -> $modpackName")
            }
        } catch (e: Exception) {
            BepInExLog.e("Failed to add mod", e)
            null
        }
    }

    fun addModFromUri(context: Context, packageName: String, modpackName: String, uri: Uri): File? {
        val fileName = resolveFileName(context, uri) ?: "mod_${System.currentTimeMillis()}.dll"
        if (!isModFileName(fileName)) {
            BepInExLog.w("Rejected mod URI with unsupported extension: $fileName")
            return null
        }
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        pluginsDir.mkdirs()
        val dest = File(pluginsDir, fileName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            syncModpackMeta(packageName, modpackName)
            BepInExLog.i("Added mod from URI: $fileName -> $modpackName")
            dest
        } catch (e: Exception) {
            BepInExLog.e("Failed to add mod from URI", e)
            null
        }
    }

    fun removeMod(file: File): Boolean {
        return file.delete().also {
            if (it) {
                val pluginsDir = generateSequence(file.parentFile) { it.parentFile }
                    .firstOrNull { it.name == "plugins" }
                val modpackDir = pluginsDir?.parentFile
                val modpacksDir = modpackDir?.parentFile
                val gameRootDir = modpacksDir?.parentFile
                val pkg = gameRootDir?.name ?: ""
                val modpackName = modpackDir?.name ?: ""
                if (pkg.isNotEmpty() && modpackName.isNotEmpty()) {
                    syncModpackMeta(pkg, modpackName)
                }
                BepInExLog.i("Removed mod: ${file.name}")
            }
        }
    }

    fun getModCount(packageName: String, modpackName: String): Int =
        listMods(packageName, modpackName).size

    // Activate / Apply / Runtime Isolation

    fun applyModpack(packageName: String, modpackName: String): Boolean {
        return try {
            restoreRuntimeState(packageName, modpackName)
            BepInExLog.i("Applied modpack: $modpackName -> active")
            true
        } catch (e: Exception) {
            BepInExLog.e("Failed to apply modpack", e)
            false
        }
    }

    /**
     * Copy multiple modpacks' plugins and configs to the active BepInEx directory.
     * Modpacks are applied in the order provided, later ones overriding earlier ones.
     */
    fun applyModpacks(packageName: String, modpackNames: List<String>): Boolean {
        if (modpackNames.isEmpty()) {
            clearActiveMods(packageName)
            return true
        }
        if (modpackNames.size == 1) {
            return applyModpack(packageName, modpackNames.first())
        }

        try {
            // Restore from the first modpack base state
            restoreRuntimeState(packageName, modpackNames.first())

            val pluginsDir = BepInExPaths.getPluginsDir(packageName)
            val configDir = BepInExPaths.getConfigDir(packageName)
            val bepInExDir = BepInExPaths.getBepInExDir(packageName)

            // Overlay remaining modpacks
            for (i in 1 until modpackNames.size) {
                val modpackName = modpackNames[i]
                val modpackDir = getModpackDir(packageName, modpackName)

                // Overlay plugins
                val modpackPlugins = getModpackPluginsDir(packageName, modpackName)
                if (modpackPlugins.isDirectory) {
                    modpackPlugins.walkTopDown().filter { it.isFile }.forEach { plugin ->
                        val rel = plugin.relativeTo(modpackPlugins)
                        val target = File(pluginsDir, rel.path)
                        target.parentFile?.mkdirs()
                        plugin.copyTo(target, overwrite = true)
                    }
                }

                // Overlay config (skip BepInEx.cfg: the core framework config is
                // global, not per-modpack)
                val modpackConfig = getModpackConfigDir(packageName, modpackName)
                if (modpackConfig.isDirectory) {
                    modpackConfig.listFiles()?.forEach { cfg ->
                        if (cfg.name != "BepInEx.cfg") {
                            cfg.copyTo(File(configDir, cfg.name), overwrite = true)
                        }
                    }
                }

                // Overlay extra files
                copyModpackRootContents(modpackDir, bepInExDir)
            }

            BepInExLog.i("Applied ${modpackNames.size} modpacks: ${modpackNames.joinToString(", ")} -> active")
            return true
        } catch (e: Exception) {
            BepInExLog.e("Failed to apply modpacks", e)
            return false
        }
    }

    fun clearActiveMods(packageName: String) {
        restoreRuntimeState(packageName, null)
        BepInExLog.i("Cleared active mods (vanilla mode)")
    }

    fun persistRuntimeState(packageName: String, modpackName: String?) {
        val destRoot = stateRoot(packageName, modpackName)
        destRoot.mkdirs()
        // Only persist configs for plugins actually in this modpack. Without
        // this, a runtime config dir holding plugins from several modpacks
        // (overlay mode, or leftovers) would leak every config into every
        // modpack on every run.
        val allowedGuids = if (!modpackName.isNullOrEmpty()) {
            getModpackPluginGuids(packageName, modpackName)
        } else {
            // Vanilla: no plugins, so persist no plugin configs. BepInEx.cfg
            // is global and never persisted per-state.
            emptySet()
        }
        // Last-writer-wins: only copy a runtime config back when it is newer than
        // the modpack's copy (or the modpack has no copy). An unconditional copy
        // would silently clobber edits the user made in the launcher UI between
        // runs with stale runtime state.
        copyNewerDirContents(
            BepInExPaths.getConfigDir(packageName),
            File(destRoot, "config"),
            allowedGuids
        )
        copyRuntimeLogs(packageName, File(destRoot, "logs"))
        BepInExLog.i("Persisted runtime cfg/logs -> ${destRoot.absolutePath}")
    }

    /**
     * Collects the BepInEx plugin GUIDs for every DLL in a modpack's plugins
     * dir, by scanning each DLL for reverse-domain strings (the BepInPlugin
     * attribute GUID is stored as UTF-16 in the assembly metadata).
     *
     * NOTE: BepInEx.cfg (the core framework config) is intentionally NOT
     * included. It is global, not per-modpack: it is never persisted into a
     * modpack and never deleted by a modpack switch.
     */
    private fun getModpackPluginGuids(packageName: String, modpackName: String): Set<String> {
        val guids = mutableSetOf<String>()
        val pluginsDir = getModpackPluginsDir(packageName, modpackName)
        if (!pluginsDir.isDirectory) return guids
        pluginsDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }
            .forEach { dll -> guids.addAll(extractPluginGuids(dll)) }
        return guids
    }

    /**
     * Scans a .NET DLL for UTF-16LE strings in reverse-domain notation
     * (e.g. com.example.mymod). Plugin GUIDs from [BepInPlugin] live in the
     * #US heap as UTF-16; matching them against actual .cfg basenames keeps
     * false positives harmless.
     */
    private fun extractPluginGuids(dll: File): Set<String> {
        val guids = mutableSetOf<String>()
        try {
            val bytes = dll.readBytes()
            var i = 0
            while (i < bytes.size - 1) {
                if (bytes[i] in 32..126 && bytes[i + 1] == 0.toByte()) {
                    val sb = StringBuilder()
                    var j = i
                    while (j < bytes.size - 1 &&
                        bytes[j] in 32..126 && bytes[j + 1] == 0.toByte()
                    ) {
                        sb.append(bytes[j].toInt().toChar())
                        j += 2
                    }
                    val s = sb.toString()
                    if (s.length >= 5 && s.contains('.') &&
                        s.all { c -> c.isLowerCase() || c.isDigit() || c == '.' || c == '_' || c == '-' }
                    ) {
                        val parts = s.split('.')
                        if (parts.size >= 2 && parts.all { it.isNotEmpty() }) {
                            guids.add(s)
                        }
                    }
                    i = j
                } else {
                    i++
                }
            }
        } catch (e: Exception) {
            BepInExLog.w("Failed to scan ${dll.name} for plugin GUIDs: ${e.message}")
        }
        return guids
    }

    /**
     * Copies source -> dest, but only for files that do not exist at the
     * destination or are newer than the destination copy. Source timestamps are
     * preserved so the next comparison stays meaningful. When [allowedNames]
     * is non-null, only files whose basename (without extension) is in the set
     * are copied.
     */
    private fun copyNewerDirContents(source: File, dest: File, allowedNames: Set<String>? = null) {
        dest.mkdirs()
        if (!source.isDirectory) return
        source.listFiles()?.forEach { child ->
            val target = File(dest, child.name)
            if (child.isDirectory) {
                copyNewerDirContents(child, target, allowedNames)
            } else {
                if (allowedNames != null && child.nameWithoutExtension !in allowedNames) {
                    return@forEach
                }
                if (!target.exists() || child.lastModified() > target.lastModified()) {
                    child.copyTo(target, overwrite = true)
                    target.setLastModified(child.lastModified())
                    BepInExLog.i("Persisted newer config: ${child.name}")
                }
            }
        }
    }

    fun restoreRuntimeState(packageName: String, modpackName: String?) {
        val srcRoot = stateRoot(packageName, modpackName)
        val bepInExDir = BepInExPaths.getBepInExDir(packageName)
        val pluginsDir = BepInExPaths.getPluginsDir(packageName)
        val configDir = BepInExPaths.getConfigDir(packageName)
        val logsDir = BepInExPaths.getLogsDir(packageName)
        val logFile = BepInExPaths.getLogFile(packageName)

        replaceDir(logsDir)
        if (logFile.exists()) logFile.delete()

        if (!modpackName.isNullOrEmpty()) {
            syncDirContents(getModpackPluginsDir(packageName, modpackName), pluginsDir)
            copyModpackRootContents(srcRoot, bepInExDir)
        } else {
            replaceDir(pluginsDir)
        }
        syncConfigDir(File(srcRoot, "config"), configDir)
        BepInExLog.i("Restored runtime cfg from ${srcRoot.absolutePath}")
    }

    /**
     * Syncs a modpack's config dir into the runtime config dir, like
     * [syncDirContents], but preserves BepInEx.cfg: the core framework config
     * is global, not per-modpack, so a modpack switch must neither delete nor
     * overwrite it.
     */
    private fun syncConfigDir(source: File, dest: File) {
        // Back up the global config before the sync wipes extras.
        val globalCfg = File(dest, "BepInEx.cfg")
        val globalBackup = if (globalCfg.isFile) globalCfg.readBytes() else null
        val globalModified = if (globalCfg.isFile) globalCfg.lastModified() else 0L

        syncDirContents(source, dest)

        // Restore the global config.
        if (globalBackup != null) {
            globalCfg.writeBytes(globalBackup)
            globalCfg.setLastModified(globalModified)
        }
    }

    private fun stateRoot(packageName: String, modpackName: String?): File =
        if (modpackName.isNullOrEmpty()) {
            BepInExPaths.getVanillaStateDir(packageName)
        } else {
            getModpackDir(packageName, modpackName)
        }

    private fun copyRuntimeLogs(packageName: String, destLogs: File) {
        destLogs.mkdirs()
        BepInExPaths.getLogFile(packageName).takeIf { it.isFile }?.copyTo(
            File(destLogs, "LogOutput.log"), overwrite = true
        )
        copyDirContents(BepInExPaths.getLogsDir(packageName), destLogs)
    }

    private fun replaceDir(dir: File) {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun copyDirContents(source: File, dest: File) {
        dest.mkdirs()
        if (!source.isDirectory) return
        source.listFiles()?.forEach { child ->
            val target = File(dest, child.name)
            if (child.isDirectory) {
                child.copyRecursively(target, overwrite = true)
            } else {
                child.copyTo(target, overwrite = true)
            }
        }
    }

    private fun syncDirContents(source: File, dest: File) {
        if (!source.isDirectory) {
            replaceDir(dest)
            return
        }

        if (dest.exists() && !dest.isDirectory) dest.delete()
        dest.mkdirs()

        val sourceChildren = source.listFiles()?.associateBy { it.name }.orEmpty()
        dest.listFiles()
            ?.filter { it.name !in sourceChildren }
            ?.forEach { it.deleteRecursively() }

        sourceChildren.values.forEach { child ->
            syncEntry(child, File(dest, child.name))
        }
    }

    private fun syncEntry(source: File, target: File) {
        if (source.isDirectory) {
            syncDirContents(source, target)
            return
        }

        if (target.isFile && filesHaveSameContent(source, target)) return
        if (target.exists()) target.deleteRecursively()
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun filesHaveSameContent(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false

        first.inputStream().buffered().use { firstInput ->
            second.inputStream().buffered().use { secondInput ->
                val firstBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val secondBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val firstRead = firstInput.read(firstBuffer)
                    val secondRead = secondInput.read(secondBuffer)
                    if (firstRead != secondRead) return false
                    if (firstRead == -1) return true
                    for (index in 0 until firstRead) {
                        if (firstBuffer[index] != secondBuffer[index]) return false
                    }
                }
            }
        }
    }

    private fun copyModpackRootContents(source: File, bepInExDir: File) {
        if (!source.isDirectory) return

        val runtimeOwned = setOf("modpack.json", "plugins", "config", "logs", "icon.png", "icon.jpg", "icon.jpeg", "icon.webp")
        source.listFiles()
            ?.filter { it.name !in runtimeOwned }
            ?.forEach { child ->
                val target = File(bepInExDir, child.name)
                syncEntry(child, target)
                BepInExLog.i("Restored modpack entry: ${child.name} -> ${target.absolutePath}")
            }
    }

    // Export / Import

    suspend fun exportModpack(
        packageName: String,
        modpackName: String,
        outputFile: File,
        onProgress: suspend (ModpackExportProgress) -> Unit = {}
    ): Boolean {
        val modpackDir = getModpackDir(packageName, modpackName)
        if (!modpackDir.exists()) return false

        val tempFile = File(outputFile.parentFile, ".${outputFile.name}.part")
        return try {
            tempFile.delete()
            var totalFiles = 0L
            var totalBytes = 0L
            for (file in modpackDir.walkTopDown()) {
                currentCoroutineContext().ensureActive()
                if (isExportableFile(modpackDir, file)) {
                    totalFiles++
                    totalBytes += file.length()
                }
            }
            onProgress(ModpackExportProgress("preparing", totalFiles = totalFiles, totalBytes = totalBytes))

            var completedFiles = 0L
            var completedBytes = 0L
            var lastProgressNanos = 0L
            suspend fun reportProgress(force: Boolean = false, currentFile: String? = null) {
                val now = System.nanoTime()
                if (force || now - lastProgressNanos >= 100_000_000L) {
                    lastProgressNanos = now
                    onProgress(
                        ModpackExportProgress(
                            phase = "exporting",
                            currentFile = currentFile,
                            completedFiles = completedFiles,
                            totalFiles = totalFiles,
                            completedBytes = completedBytes,
                            totalBytes = totalBytes
                        )
                    )
                }
            }

            val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile)))
            try {
                for (file in modpackDir.walkTopDown()) {
                    currentCoroutineContext().ensureActive()
                    if (!isExportableFile(modpackDir, file)) continue
                    val entryName = "$modpackName/${file.relativeTo(modpackDir).path.replace('\\', '/')}"
                    zos.putNextEntry(ZipEntry(entryName))
                    try {
                        val input = BufferedInputStream(file.inputStream())
                        try {
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                            var read: Int
                            do {
                                currentCoroutineContext().ensureActive()
                                read = input.read(buffer)
                                if (read > 0) {
                                    zos.write(buffer, 0, read)
                                    completedBytes += read
                                    reportProgress(currentFile = file.name)
                                }
                            } while (read >= 0)
                        } finally {
                            input.close()
                        }
                    } finally {
                        zos.closeEntry()
                    }
                    completedFiles++
                    reportProgress(force = true, currentFile = file.name)
                }
            } finally {
                zos.close()
            }

            currentCoroutineContext().ensureActive()
            if (outputFile.exists() && !outputFile.delete()) {
                throw java.io.IOException("Unable to replace existing export: ${outputFile.absolutePath}")
            }
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = false)
                tempFile.delete()
            }
            onProgress(
                ModpackExportProgress(
                    phase = "complete",
                    completedFiles = totalFiles,
                    totalFiles = totalFiles,
                    completedBytes = totalBytes,
                    totalBytes = totalBytes
                )
            )
            BepInExLog.i("Exported modpack: $modpackName -> ${outputFile.absolutePath}")
            true
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            BepInExLog.e("Failed to export modpack", e)
            false
        }
    }

    /**
     * Export a modpack to a location the user picks with the system document
     * picker (SAF `CreateDocument`).
     *
     * This is the path a build with no storage permission must take. Writing to a
     * File the app picks itself needs a storage permission; writing to a URI the
     * user granted needs none, because the grant is per-file. That is why import
     * already works this way (OpenDocument) and why export now does too.
     *
     * The archive is built in the app's own cache first and only then streamed to
     * the destination. Two reasons: a destination stream cannot be rewound if the
     * export fails part-way, which would leave a truncated zip for the user to
     * notice and delete by hand; and building away from the destination keeps the
     * provider call short rather than holding a SAF grant open for the whole walk.
     *
     * Returns the number of bytes written, or null if the destination could not be
     * opened or the export failed. Cancellation propagates and deletes the staging
     * file without touching what the user chose.
     */
    suspend fun exportModpackToUri(
        packageName: String,
        modpackName: String,
        destination: Uri,
        context: Context,
        onProgress: suspend (ModpackExportProgress) -> Unit = {}
    ): Long? {
        val modpackDir = getModpackDir(packageName, modpackName)
        if (!modpackDir.exists()) return null

        // An exported archive must be able to come back in through
        // importModpack, which rejects anything without a modpack.json. A modpack
        // created in the app always has one (createModpack writes it), but a
        // modpack assembled from outside - a .dll pushed with adb, a directory
        // the user dropped in - does not, and exporting it produced an archive
        // that import then refused with "Modpack archive is missing modpack.json".
        // The export reported success and produced an unusable file, which is the
        // worst shape: it succeeds and produces something that cannot come back.
        // Write the manifest when absent so the round trip holds however the
        // modpack came to exist. Measured on device, not hypothetical.
        ensureManifest(modpackDir, packageName, modpackName)

        val staging = File(context.cacheDir, "export-${modpackName}-${System.currentTimeMillis()}.zip")
        try {
            if (!exportModpack(packageName, modpackName, staging, onProgress)) return null

            return try {
                val output = context.contentResolver.openOutputStream(destination, "w")
                    ?: return null
                output.use { out ->
                    staging.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
                val size = staging.length()
                BepInExLog.i("Exported modpack via SAF: $modpackName -> $destination ($size bytes)")
                size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BepInExLog.e("Failed to export modpack to chosen destination", e)
                null
            }
        } finally {
            staging.delete()
        }
    }

    private fun isExportableFile(modpackDir: File, file: File): Boolean {
        if (!file.isFile) return false
        val relativePath = file.relativeTo(modpackDir).invariantSeparatorsPath
        return !relativePath.substringBefore('/').equals("logs", ignoreCase = true)
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun importModpack(
        packageName: String,
        uri: Uri,
        context: Context,
        archiveName: String? = null
    ): ModpackMeta? {
        val effectiveArchiveName = archiveName
            ?: resolveDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: "modpack.zip"

        if (!isModpackFileName(effectiveArchiveName)) {
            BepInExLog.w("Rejected modpack import with unsupported extension: $effectiveArchiveName")
            return null
        }
        val requestedName = normalizeModpackName(
            effectiveArchiveName.substringBeforeLast('.')
        )
        val resolvedName = requestedName.ifEmpty { "imported_${System.currentTimeMillis()}" }
        val modpackDir = getModpackDir(packageName, resolvedName)
        val stagingDir = File(
            modpackDir.parentFile,
            ".${resolvedName}.import-${System.currentTimeMillis()}"
        )

        return try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            val stagingRoot = stagingDir.canonicalFile.toPath()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Unable to open modpack archive")
            try {
                val zis = ZipInputStream(BufferedInputStream(input))
                try {
                    var entry = zis.nextEntry
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        val normalizedEntryName = entry.name.replace('\\', '/').trimStart('/')
                        if (normalizedEntryName.isNotEmpty()) {
                            val entryFile = File(stagingDir, normalizedEntryName).canonicalFile
                            if (!entryFile.toPath().startsWith(stagingRoot)) {
                                throw java.io.IOException("Unsafe archive entry: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                entryFile.mkdirs()
                            } else {
                                entryFile.parentFile?.mkdirs()
                                val output = BufferedOutputStream(FileOutputStream(entryFile))
                                try {
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                                    var read: Int
                                    do {
                                        currentCoroutineContext().ensureActive()
                                        read = zis.read(buffer)
                                        if (read > 0) output.write(buffer, 0, read)
                                    } while (read >= 0)
                                } finally {
                                    output.close()
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                } finally {
                    zis.close()
                }
            } finally {
                input.close()
            }

            currentCoroutineContext().ensureActive()
            modpackDir.parentFile?.mkdirs()
            val children = stagingDir.listFiles().orEmpty()
            val sourceDir = if (children.size == 1 && children[0].isDirectory) children[0] else stagingDir
            val metadataFile = sourceDir.walkTopDown().firstOrNull { file ->
                file.isFile && file.name.equals("modpack.json", ignoreCase = true)
            } ?: throw java.io.IOException("Modpack archive is missing modpack.json")
            JSONObject(metadataFile.readText())
            val contentRoot = metadataFile.parentFile
                ?: throw java.io.IOException("Invalid modpack metadata location")

            if (modpackDir.exists()) modpackDir.deleteRecursively()
            if (!contentRoot.renameTo(modpackDir)) {
                contentRoot.copyRecursively(modpackDir, overwrite = true)
            }
            stagingDir.deleteRecursively()

            getModpackPluginsDir(packageName, resolvedName).mkdirs()
            getModpackConfigDir(packageName, resolvedName).mkdirs()
            getModpackLogsDir(packageName, resolvedName).mkdirs()

            val meta = ModpackMeta(
                name = resolvedName,
                packageName = packageName,
                modCount = getModCount(packageName, resolvedName)
            )
            writeMeta(meta)
            BepInExLog.i("Imported modpack: $resolvedName")
            meta
        } catch (e: CancellationException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            BepInExLog.e("Failed to import modpack", e)
            null
        }
    }

    // Metadata persistence

    private fun readMeta(packageName: String, name: String): ModpackMeta? {
        val file = getMetaFile(packageName, name)
        val actualModCount = getModCount(packageName, name)
        if (!file.exists()) {
            return ModpackMeta(
                name = name,
                packageName = packageName,
                modCount = actualModCount
            )
        }
        return try {
            val json = JSONObject(file.readText())
            ModpackMeta(
                name = name,
                packageName = packageName,
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                modCount = actualModCount
            )
        } catch (e: Exception) {
            ModpackMeta(name = name, packageName = packageName, modCount = actualModCount)
        }
    }

    private fun writeMeta(meta: ModpackMeta) {
        val json = JSONObject().apply {
            put("name", meta.name)
            put("packageName", meta.packageName)
            put("createdAt", meta.createdAt)
            put("modCount", meta.modCount)
        }
        getMetaFile(meta.packageName, meta.name).writeText(json.toString(2))
    }

    /**
     * Make sure the modpack directory carries the manifest import requires.
     *
     * Existing manifests are left alone: modCount in a real one is maintained by
     * addMod/removeMod, and rewriting it here would lose that. Only a missing
     * manifest is synthesised, from the current on-disk plugin count.
     */
    private fun ensureManifest(modpackDir: File, packageName: String, modpackName: String) {
        val manifest = File(modpackDir, "modpack.json")
        if (manifest.isFile) return
        try {
            val meta = ModpackMeta(
                name = modpackName,
                packageName = packageName,
                modCount = getModCount(packageName, modpackName)
            )
            manifest.writeText(
                JSONObject().apply {
                    put("name", meta.name)
                    put("packageName", meta.packageName)
                    put("createdAt", meta.createdAt)
                    put("modCount", meta.modCount)
                }.toString(2)
            )
            BepInExLog.i("Synthesised missing modpack.json for export: $modpackName")
        } catch (e: Exception) {
            BepInExLog.e("Could not synthesise modpack.json for export: $modpackName", e)
        }
    }

    // Icon

    fun getModpackIconFile(packageName: String, modpackName: String): File? {
        val dir = getModpackDir(packageName, modpackName)
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("icon.") &&
                it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp")
        }
    }

    fun saveModpackIcon(packageName: String, modpackName: String, bitmap: Bitmap, extension: String = "png") {
        getModpackIconFile(packageName, modpackName)?.delete()
        val file = File(getModpackDir(packageName, modpackName), "icon.$extension")
        file.outputStream().use { out ->
            val format = when (extension.lowercase()) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.PNG
            }
            bitmap.compress(format, 90, out)
        }
    }

    fun deleteModpackIcon(packageName: String, modpackName: String) {
        getModpackIconFile(packageName, modpackName)?.delete()
    }

    fun hasModpackIcon(packageName: String, modpackName: String): Boolean =
        getModpackIconFile(packageName, modpackName) != null
}
