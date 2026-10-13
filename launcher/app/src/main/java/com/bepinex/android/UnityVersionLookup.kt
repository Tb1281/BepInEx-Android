package com.bepinex.android

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Reads the Unity editor version embedded in common Unity data files.
 *
 * Ported and adapted from FusionCore's VersionLookup.
 */
object UnityVersionLookup {
    private const val MAX_VERSION_LENGTH = 32
    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+(?:[abcfp]\\d+|rc\\d+)?$")

    private val candidates = linkedMapOf(
        "globalgamemanagers" to intArrayOf(0x14, 0x30),
        "data.unity3d" to intArrayOf(0x12),
        "mainData" to intArrayOf(0x14)
    )

    /**
     * Search for Unity version string in known data file headers within a folder.
     */
    fun find(dataFolder: File): String? {
        for ((fileName, offsets) in candidates) {
            val file = File(dataFolder, fileName)
            if (!file.isFile) continue

            try {
                RandomAccessFile(file, "r").use { reader ->
                    for (offset in offsets) {
                        if (offset < 0 || offset >= reader.length()) continue
                        reader.seek(offset.toLong())
                        val value = readAscii(reader)
                        if (value != null && versionPattern.matches(value)) return value
                    }
                }
            } catch (e: Exception) {
                BepInExLog.d("Unity version lookup failed for ${file.absolutePath}: ${e.message}")
            }
        }
        return null
    }

    /**
     * Search for Unity version string inside an APK ZipFile.
     * Reads only the necessary header bytes without extracting the full file.
     */
    fun findInZip(zip: ZipFile): String? {
        for ((fileName, offsets) in candidates) {
            val entry = zip.getEntry("assets/bin/Data/$fileName")
                ?: zip.getEntry("assets/$fileName")
                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("/$fileName") }
                ?: continue

            try {
                zip.getInputStream(entry).use { stream ->
                    val maxOffset = (offsets.maxOrNull() ?: 0) + MAX_VERSION_LENGTH
                    val buffer = ByteArray(maxOffset)
                    var totalRead = 0
                    while (totalRead < maxOffset) {
                        val read = stream.read(buffer, totalRead, maxOffset - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                    for (offset in offsets) {
                        if (offset < 0 || offset >= totalRead) continue
                        val value = readAsciiFromBuffer(buffer, offset)
                        if (value != null && versionPattern.matches(value)) return value
                    }
                }
            } catch (e: Exception) {
                BepInExLog.d("Unity version lookup failed for zip entry ${entry.name}: ${e.message}")
            }
        }
        return null
    }

    /**
     * Search for Unity version string directly in an APK file.
     */
    fun findInApk(apkFile: File): String? {
        if (!apkFile.isFile) return null
        return try {
            ZipFile(apkFile).use { zip ->
                findInZip(zip)
            }
        } catch (e: Exception) {
            BepInExLog.d("Failed to read Unity version from ${apkFile.absolutePath}: ${e.message}")
            null
        }
    }

    private fun readAscii(reader: RandomAccessFile): String? {
        val value = StringBuilder(MAX_VERSION_LENGTH)
        for (index in 0 until MAX_VERSION_LENGTH) {
            val byte = reader.read()
            if (byte < 0 || byte == 0) break
            if (byte < 0x20 || byte > 0x7e) break
            value.append(byte.toChar())
        }
        return value.toString().trim().ifEmpty { null }
    }

    private fun readAsciiFromBuffer(buffer: ByteArray, offset: Int): String? {
        val value = StringBuilder(MAX_VERSION_LENGTH)
        for (i in offset until minOf(offset + MAX_VERSION_LENGTH, buffer.size)) {
            val b = buffer[i].toInt() and 0xFF
            if (b == 0 || b < 0x20 || b > 0x7e) break
            value.append(b.toChar())
        }
        return value.toString().trim().ifEmpty { null }
    }

    fun normalize(version: String): String =
        Regex("^(\\d+\\.\\d+\\.\\d+)").find(version.trim())?.groupValues?.get(1)
            ?: version.trim()
}
