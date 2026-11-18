package com.bepinex.android.fusion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/**
 * Version identity shared by every cache that must be invalidated when a game
 * is updated. Keeping all stamps on one format makes them change together.
 */
object PackageVersion {
    /**
     * Returns `<longVersionCode>:<versionName>` for [context]'s package, or
     * `"unknown"` when the package info cannot be read.
     */
    fun stamp(context: Context): String =
        stamp(context.packageManager, context.packageName)

    /**
     * Returns `<longVersionCode>:<versionName>` for [packageName], or
     * `"unknown"` when the package info cannot be read.
     */
    fun stamp(packageManager: PackageManager, packageName: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        "${PackageInfoCompat.getLongVersionCode(info)}:${info.versionName}"
    } catch (_: Exception) {
        "unknown"
    }
}
