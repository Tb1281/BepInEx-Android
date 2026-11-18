package com.bepinex.android.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staged config is parsed by fusion.cpp, so its wire format is part of the
 * native contract. In particular a split install must carry the resolved
 * `libunity.so` path (possibly an APK entry) rather than reconstructing it from
 * an empty `nativeLibraryDir`.
 */
class FusionConfigTest {

    @Test
    fun `game unity library path is serialized`() {
        val unityPath = "/data/app/game/base.apk!/lib/arm64-v8a/libunity.so"

        val lines = config(gameUnityLibraryPath = unityPath).toConfigFile().lines()

        assertTrue(lines.contains("gameUnityLibraryPath=$unityPath"))
    }

    @Test
    fun `game unity library path defaults to empty`() {
        val config = config()

        assertEquals("", config.gameUnityLibraryPath)
        assertTrue(config.toConfigFile().lines().contains("gameUnityLibraryPath="))
    }

    @Test
    fun `useOriginalLibUnity is serialized`() {
        val text = config(useOriginalLibUnity = true).toConfigFile()

        assertTrue(text.lines().contains("useOriginalLibUnity=true"))
    }

    private fun config(
        useOriginalLibUnity: Boolean = false,
        gameUnityLibraryPath: String = "",
    ) = FusionConfig(
        gameLibraryDirectory = "/game/lib",
        appLibraryDirectory = "/app/lib",
        appDataDirectory = "/data/app/files",
        bepInExDirectory = "/sdcard/BepInEx",
        dotnetDirectory = "/data/app/dotnet",
        unityDataDirectory = "/data/app/Data",
        unityVersion = "2022.3.62f3",
        useOriginalLibUnity = useOriginalLibUnity,
        gameUnityLibraryPath = gameUnityLibraryPath,
        externalFilesDirectory = "/sdcard/Android/data",
    )
}
