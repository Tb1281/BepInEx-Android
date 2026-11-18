package com.bepinex.android.fusion

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Split-install resolution is the path that makes a game installed with ABI
 * splits behave like one whose libraries were extracted by the platform.
 *
 * These run against real APK-shaped ZIP files on the JVM (no Android runtime),
 * exercising [GameNativeLibraries.resolveAndStage] directly:
 *  - a populated nativeLibraryDir needs no staging and ignores stored APK
 *    entries, keeping the monolithic load path;
 *  - an empty nativeLibraryDir with a STORED split maps libunity from the APK
 *    while staging the rest;
 *  - a DEFLATED split cannot be mapped, so the staged copy is used;
 *  - companion native libraries are staged too;
 *  - the version stamp makes staging a no-op and invalidates it on update.
 */
class GameNativeLibrariesTest {

    private companion object {
        const val STAMP = ".extracted-version"
    }

    private val abi = "arm64-v8a"
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("game-native-libraries-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `direct nativeLibraryDir is used without staging`() {
        val direct = directLibraryDir()
        val destination = File(root, "game-libs/$abi")

        val layout = resolve(
            nativeLibraryDir = direct.absolutePath,
            apkPaths = emptyList(),
            destination = destination,
        )

        assertEquals(direct.absolutePath, layout.directory)
        assertEquals(File(direct, "libunity.so").absolutePath, layout.unityLibraryPath)
        assertFalse("populated nativeLibraryDir must not stage", destination.exists())
    }

    @Test
    fun `direct nativeLibraryDir ignores stored apk entries`() {
        val direct = directLibraryDir()
        val apk = apk(
            method = ZipEntry.STORED,
            "lib/$abi/libunity.so" to "unity",
            "lib/$abi/libil2cpp.so" to "il2cpp",
        )
        val destination = File(root, "game-libs/$abi")

        val layout = resolve(
            nativeLibraryDir = direct.absolutePath,
            apkPaths = listOf(apk.absolutePath),
            destination = destination,
        )

        assertEquals(direct.absolutePath, layout.directory)
        assertEquals(
            "a populated nativeLibraryDir keeps the monolithic load path",
            File(direct, "libunity.so").absolutePath,
            layout.unityLibraryPath,
        )
        assertFalse("populated nativeLibraryDir must not stage", destination.exists())
    }

    @Test
    fun `stored split apk keeps libunity as an apk entry and stages the rest`() {
        val apk = apk(
            method = ZipEntry.STORED,
            "lib/$abi/libunity.so" to "unity",
            "lib/$abi/libil2cpp.so" to "il2cpp",
        )
        val destination = File(root, "game-libs/$abi")

        val layout = resolve(
            nativeLibraryDir = "",
            apkPaths = listOf(apk.absolutePath),
            destination = destination,
        )

        assertEquals(
            "${apk.absolutePath}!/lib/$abi/libunity.so",
            layout.unityLibraryPath,
        )
        assertEquals(destination.absolutePath, layout.directory)
        assertEquals("unity", File(destination, "libunity.so").readText())
        assertEquals("il2cpp", File(destination, "libil2cpp.so").readText())
        assertEquals("1:1.0", File(destination, STAMP).readText())
    }

    @Test
    fun `deflated split apk falls back to the staged libunity`() {
        val apk = apk(
            method = ZipEntry.DEFLATED,
            "lib/$abi/libunity.so" to "unity",
            "lib/$abi/libil2cpp.so" to "il2cpp",
        )
        val destination = File(root, "game-libs/$abi")

        val layout = resolve(
            nativeLibraryDir = "",
            apkPaths = listOf(apk.absolutePath),
            destination = destination,
        )

        assertEquals(
            File(destination, "libunity.so").absolutePath,
            layout.unityLibraryPath,
        )
        assertEquals("unity", File(destination, "libunity.so").readText())
    }

    @Test
    fun `companion split libraries are staged`() {
        val apk = apk(
            method = ZipEntry.DEFLATED,
            "lib/$abi/libunity.so" to "unity",
            "lib/$abi/libil2cpp.so" to "il2cpp",
            "lib/$abi/libcompanion.so" to "companion",
        )
        val destination = File(root, "game-libs/$abi")

        resolve(
            nativeLibraryDir = "",
            apkPaths = listOf(apk.absolutePath),
            destination = destination,
        )

        assertEquals("companion", File(destination, "libcompanion.so").readText())
    }

    @Test
    fun `matching stamp makes staging a cache hit`() {
        val apk = apk(
            method = ZipEntry.DEFLATED,
            "lib/$abi/libunity.so" to "unity",
            "lib/$abi/libil2cpp.so" to "il2cpp",
        )
        val destination = File(root, "game-libs/$abi")

        resolve(
            nativeLibraryDir = "",
            apkPaths = listOf(apk.absolutePath),
            destination = destination,
            version = "1:1.0",
        )
        File(destination, "libunity.so").writeText("locally-modified")
        File(destination, "keep.txt").writeText("keep")

        val layout = resolve(
            nativeLibraryDir = "",
            apkPaths = listOf(apk.absolutePath),
            destination = destination,
            version = "1:1.0",
        )

        assertEquals("locally-modified", File(destination, "libunity.so").readText())
        assertTrue("cache hit must not clear staged files", File(destination, "keep.txt").isFile)
        assertEquals(
            File(destination, "libunity.so").absolutePath,
            layout.unityLibraryPath,
        )
    }

    @Test
    fun `changed version invalidates the stamp and re-stages`() {
        val apk = apk(
            method = ZipEntry.DEFLATED,
            "lib/$abi/libunity.so" to "unity",
            "lib/$abi/libil2cpp.so" to "il2cpp",
        )
        val destination = File(root, "game-libs/$abi")

        resolve(
            nativeLibraryDir = "",
            apkPaths = listOf(apk.absolutePath),
            destination = destination,
            version = "1:1.0",
        )
        File(destination, "libunity.so").writeText("stale")
        File(destination, "obsolete.txt").writeText("old")

        resolve(
            nativeLibraryDir = "",
            apkPaths = listOf(apk.absolutePath),
            destination = destination,
            version = "2:2.0",
        )

        assertEquals("unity", File(destination, "libunity.so").readText())
        assertFalse("stale files are cleared on re-stage", File(destination, "obsolete.txt").exists())
        assertEquals("2:2.0", File(destination, STAMP).readText())
    }

    private fun directLibraryDir(): File =
        File(root, "direct").apply {
            mkdirs()
            File(this, "libunity.so").writeText("unity")
            File(this, "libil2cpp.so").writeText("il2cpp")
        }

    private fun resolve(
        nativeLibraryDir: String,
        apkPaths: List<String>,
        destination: File,
        version: String = "1:1.0",
    ): GameNativeLibraries.Layout = GameNativeLibraries.resolveAndStage(
        nativeLibraryDir = nativeLibraryDir,
        apkPaths = apkPaths,
        abi = abi,
        version = version,
        destination = destination,
    )

    private fun apk(method: Int, vararg entries: Pair<String, String>): File {
        val file = File(root, "split-${System.nanoTime()}.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                val bytes = text.toByteArray()
                val entry = ZipEntry(name).apply {
                    this.method = method
                    if (method == ZipEntry.STORED) {
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
