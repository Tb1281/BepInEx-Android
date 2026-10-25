package com.bepinex.android.hooks

import android.content.Context
import com.bepinex.android.BepInExLog

/**
 * Process-wide module shield for integrity VMs such as PairIP.
 *
 * Protected apps enumerate loaded ELF objects with `dl_iterate_phdr` and treat
 * host libraries as tampering. The native shield must be installed before the
 * protected app's first VM program runs, so it deliberately has no dependency
 * on LSPlant or the Java hook engine.
 *
 * Hiding policy: only host/instrumentation modules are removed. Platform,
 * game-APK and game-data modules remain visible, which keeps normal framework
 * lookups and the game's own integrity checks working.
 */
object PairIpShield {
    private const val TAG = "PairIpShield"

    @Volatile
    private var installed = false

    /**
     * Loads the shield and installs the host's hiding policy. Safe to call more
     * than once; the native hook is installed once per process.
     *
     * @param hostContext the launcher's own context, used to hide the
     *     launcher's native-library directory and data directory.
     * @param gameContext the hosted game's context, whose native libraries
     *     must stay visible.
     */
    fun install(
        hostContext: Context,
        gameContext: Context,
        allowedPrefixes: List<String> = emptyList(),
    ) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                System.loadLibrary("pairipshield")
            } catch (t: Throwable) {
                BepInExLog.e("$TAG: failed to load native shield", t)
                return
            }

            val hostLibDir = hostContext.applicationInfo.nativeLibraryDir
            val hostDataDir = hostContext.applicationInfo.dataDir
            val gameLibDir = gameContext.applicationInfo.nativeLibraryDir

            // Host APK libraries and host-private state are never part of the
            // game's expected environment. Game libraries stay visible even
            // when they were extracted out of an ABI split into host storage.
            val prefixes = arrayOf(
                hostLibDir,
                hostDataDir,
            ).filter { it.isNotBlank() && it != gameLibDir }.toTypedArray()
            val allowed = allowedPrefixes.filter { it.isNotBlank() }.toTypedArray()

            val substrings = arrayOf(
                "libpairipshield",
                "libaudiopolicy",
                "liblsplant",
                "libhookengine",
                "libdobby",
                "frida",
                "gadget",
                "xposed",
                "lsposed",
                "substrate",
                "magisk",
                "/data/local/tmp",
                "/data/adb",
            )

            nativeConfigure(prefixes, substrings, allowed)
            installed = nativeIsActive()
            BepInExLog.i("$TAG: module shield installed=$installed")
        }
    }

    fun isInstalled(): Boolean = installed

    private external fun nativeConfigure(
        prefixes: Array<String>,
        substrings: Array<String>,
        allowedPrefixes: Array<String>,
    )
    private external fun nativeIsActive(): Boolean
}
