package com.bepinex.android.hooks

import android.content.Context
import com.bepinex.android.BepInExLog
import java.io.File

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
     * Loads the native shield library without configuring or installing it.
     * The same library exposes the class-loader bridge used to preload
     * `libmain.so` into the game's linker namespace, which must work even in
     * the debug-only "no shield" experiments.
     */
    fun ensureLoaded() {
        System.loadLibrary("pairipshield")
    }

    /**
     * Loads [libraryPath] into [loader]'s linker namespace.
     *
     * @return null on success, or the platform's error message.
     */
    fun loadIntoClassLoader(loader: ClassLoader, libraryPath: String): String? {
        ensureLoaded()
        return nativeLoadIntoClassLoader(loader, libraryPath)
    }

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
            val hostApkDir = File(hostContext.packageCodePath).parent
            val hostExternalDir = hostContext.getExternalFilesDir(null)?.absolutePath
            val gameLibDir = gameContext.applicationInfo.nativeLibraryDir

            // Host APK libraries and host-private state are never part of the
            // game's expected environment. Game libraries stay visible even
            // when they were extracted out of an ABI split into host storage.
            val prefixes = listOfNotNull(
                hostLibDir,
                hostDataDir,
                hostApkDir,
                hostExternalDir,
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
            nativeSetGameUid(gameContext.applicationInfo.uid)
            installed = nativeIsActive()
            BepInExLog.i("$TAG: module shield installed=$installed")
        }
    }

    fun isInstalled(): Boolean = installed

    /**
     * Debug-only: captures the immediate caller and interpreter frame of the
     * PairIP VM string-append helper. No-op in production because the only
     * caller is the debuggable-only experiment path.
     */
    fun installTrace(): Boolean {
        ensureLoaded()
        return nativeInstallTrace()
    }

    /**
     * Debug-only: hooks the VM's class resolver and logs the descriptor,
     * table, key and decoded entry for each uncached lookup. Distinguishes a
     * stale hosted-mode literal from a genuinely missing class. Installed
     * after the startup program and before `libmain.so` runs klB.
     */
    fun installClassTrace(): Boolean {
        ensureLoaded()
        return nativeInstallClassTrace()
    }

    /**
     * Debug-only: patches the process-wide JNIEnv table and logs every JNI
     * call made directly from `libpairipcore.so`. The table is data-only, so
     * the VM's own ELF/maps checks still observe an unmodified process.
     * Installed after the startup program and before `libmain.so` runs klB.
     */
    fun installJniTrace(): Boolean {
        ensureLoaded()
        return nativeInstallJniTrace()
    }

    /**
     * Debug-only: intercepts the PairIP VM's raw-syscall wrapper and serves a
     * same-sized copy of `/proc/<pid>/maps` with host-only lines blanked.
     * Installed after the startup program and before `libmain.so` runs klB.
     */
    fun installProcSanitizer(): Boolean {
        ensureLoaded()
        return nativeInstallProcSanitizer()
    }

    /**
     * Debug-only: records the last raw syscalls issued through PairIP's own
     * syscall wrapper and dumps them when the klB reader is about to crash.
     * Used to tell whether the repeating VM loop is polling a file, a futex
     * or a clock.
     */
    fun installSyscallTrace(): Boolean {
        ensureLoaded()
        return nativeInstallSyscallTrace()
    }

    /**
     * Debug-only: maps [apkPath]'s ZIP central directory and points the klB
     * program's unresolved ZIP-view literals at it. Must be called after
     * `libpairipcore.so` is mapped and before `libmain.so` starts klB.
     *
     * @return null on success, or a diagnostic message.
     */
    fun installFakeCentralDirectory(apkPath: String): String? {
        ensureLoaded()
        return nativeInstallFakeCentralDirectory(apkPath)
    }

    /**
     * Debug-only: map the whole installed game base APK read-only at file
     * offset 0. Stock opens the base APK as an archive at offset 0; the
     * hosted process only has non-zero-offset mappings. This tests whether
     * PairIP's central-directory resolver needs the offset-0 mapping before
     * klB builds its reader. Must be called before `libmain.so` starts klB.
     *
     * @return null on success, or a diagnostic message.
     */
    fun installApkZeroMap(apkPath: String): String? {
        ensureLoaded()
        return nativeInstallApkZeroMap(apkPath)
    }

    /**
     * Debug-only: map the installed game base APK's real central directory
     * (`PK\x01\x02`) at the low hint used by the offset-0 diagnostic. klB's
     * maps reader consumes only the first 146,432 bytes, while the real
     * `r--s 074f5000` line starts at byte 316,554. This duplicate puts an
     * identical CD line inside the scanned prefix without modifying the APK.
     * Must be called before `libmain.so` starts klB.
     *
     * @return null on success, or a diagnostic message.
     */
    fun installApkCdMap(apkPath: String): String? {
        ensureLoaded()
        return nativeInstallApkCdMap(apkPath)
    }

    /**
     * Debug-only: writes the writable VM image that `executeVM` builds for klB
     * to `klB-image.bin` under [directory], before the interpreter starts.
     * Diffing it against the APK asset shows which bytes the loader decrypts
     * or relocates, and which literals klB still reads as ciphertext.
     *
     * @return null on success, or a diagnostic message.
     */
    fun installVmDump(directory: String): String? {
        ensureLoaded()
        return nativeInstallVmDump(directory)
    }

    /**
     * Debug-only: neutralize the `klB1wZSojiVvDvCN` PairIP startup program.
     * The hook is installed before `libmain.so` runs libunity's DT_INIT.
     *
     * @return true when the `ExecuteProgram` hook is installed.
     */
    fun installSkipKlbProgram(): Boolean {
        ensureLoaded()
        return nativeInstallSkipKlb()
    }

    /**
     * Debug-only: turn klB's unmapped 46-byte ZIP probe into the reader's
     * normal zero-length/overflow result. Installed after YTw and before
     * libmain lets libunity's DT_INIT run klB.
     *
     * @return true when the reader helper hook is installed.
     */
    fun installReaderGuard(): Boolean {
        ensureLoaded()
        return nativeInstallReaderGuard()
    }

    /**
     * Debug-only: rewrites this process's argv0 and task name to [name]. The
     * old argv0 buffer is reused when it is long enough, so the kernel's
     * `/proc/self/cmdline` and `/proc/self/status` `Name` look like the target
     * game's stock process instead of the launcher's `:game` process.
     *
     * @return null on success, or a diagnostic message.
     */
    fun spoofProcessName(name: String): String? {
        ensureLoaded()
        return nativeSpoofProcessName(name)
    }

    private external fun nativeConfigure(
        prefixes: Array<String>,
        substrings: Array<String>,
        allowedPrefixes: Array<String>,
    )
    private external fun nativeIsActive(): Boolean
    private external fun nativeSetGameUid(uid: Int)
    private external fun nativeInstallTrace(): Boolean
    private external fun nativeInstallClassTrace(): Boolean
    private external fun nativeInstallJniTrace(): Boolean
    private external fun nativeInstallSyscallTrace(): Boolean
    private external fun nativeInstallProcSanitizer(): Boolean
    private external fun nativeInstallFakeCentralDirectory(apkPath: String): String?
    private external fun nativeInstallApkZeroMap(apkPath: String): String?
    private external fun nativeInstallApkCdMap(apkPath: String): String?
    private external fun nativeInstallVmDump(directory: String): String?
    private external fun nativeInstallSkipKlb(): Boolean
    private external fun nativeInstallReaderGuard(): Boolean
    private external fun nativeSpoofProcessName(name: String): String?

    private external fun nativeLoadIntoClassLoader(
        loader: ClassLoader,
        libraryPath: String,
    ): String?
}
