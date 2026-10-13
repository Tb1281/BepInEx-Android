package com.bepinex.android.fusion

import android.content.pm.PackageManager
import com.bepinex.android.BepInExLog
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Method

/**
 * Pine hooks on PackageManager to suppress PlayCore AssetPackDelivery errors.
 *
 * Some Unity games use Google Play Asset Delivery. When running outside
 * the official Play Store environment, calls to PlayCore components fail
 * with "does not exist" errors. We suppress these errors so the game
 * can continue running.
 *
 * Note: Pine cannot hook abstract methods, so we find the concrete
 * implementation (ApplicationPackageManager) and hook that.
 *
 * @see PackageManagerHooks.java in FusionCore main branch
 */
object PackageManagerHooks {

    private const val PLAY_CORE_PREFIX = "com.google.android.play.core.assetpacks."

    /**
     * Install hooks on PackageManager.setComponentEnabledSetting() to
     * silently swallow calls targeting PlayCore components.
     */
    fun installHooks(packageManager: PackageManager) {
        hookComponentSettings(packageManager)
        hookApplicationInfo(packageManager)
    }

    /**
     * Report our own package as debuggable to game-side readers.
     *
     * The hosted game branches on FLAG_DEBUGGABLE (debuggable builds take its
     * tested Auth-Prod/Firestore path and never touch the Firebase C++
     * Realtime-Database path that cannot resolve its bridge classes in a
     * hosted process). Our manifest is not debuggable, so release builds
     * would take the production branch and die in Database::GetInstance.
     * OR-ing the flag into the in-process copy only affects Java readers
     * inside this process; the system's package record (signing, Play
     * Integrity, JDWP) is untouched.
     */
    private fun hookApplicationInfo(packageManager: PackageManager) {
        try {
            val pmClass = packageManager.javaClass
            val candidates = pmClass.declaredMethods.filter { m ->
                m.name == "getApplicationInfo" && m.parameterTypes.isNotEmpty() &&
                    m.parameterTypes[0] == String::class.java
            }
            if (candidates.isEmpty()) {
                BepInExLog.w("PM getApplicationInfo not found on ${pmClass.name}")
                return
            }
            for (method in candidates) {
                try {
                    method.isAccessible = true
                    Pine.hook(method, object : MethodHook() {
                        override fun afterCall(callFrame: Pine.CallFrame) {
                            try {
                                val info = callFrame.result as? android.content.pm.ApplicationInfo ?: return
                                if (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) return
                                info.flags = info.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
                                BepInExLog.d("PM spoof: FLAG_DEBUGGABLE set on ${info.packageName}")
                            } catch (_: Exception) {
                            }
                        }
                    })
                    BepInExLog.i("Hooking PM: ${pmClass.name}.${method.name}/${method.parameterTypes.size}")
                } catch (e: Exception) {
                    BepInExLog.e("Failed to hook getApplicationInfo", e)
                }
            }
        } catch (e: Exception) {
            BepInExLog.e("Failed to install getApplicationInfo hook", e)
        }
    }

    private fun hookComponentSettings(packageManager: PackageManager) {
        try {
            // We can't hook the abstract PackageManager method directly.
            // Find the concrete implementation class and hook that.
            val pmClass = packageManager.javaClass
            BepInExLog.i("PM concrete class: ${pmClass.name}")

            // Try the concrete method on the implementation class
            val method = try {
                pmClass.getDeclaredMethod(
                    "setComponentEnabledSetting",
                    android.content.ComponentName::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!
                )
            } catch (e: NoSuchMethodException) {
                // Fallback: find by name
                pmClass.declaredMethods.firstOrNull {
                    it.name == "setComponentEnabledSetting" &&
                    it.parameterTypes.size == 3
                } ?: throw e
            }

            BepInExLog.i("Hooking PM: ${pmClass.name}.${method.name}")

            Pine.hook(method, object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    try {
                        val componentName = callFrame.args[0] as? android.content.ComponentName
                        val className = componentName?.className ?: return

                        if (className.startsWith(PLAY_CORE_PREFIX)) {
                            BepInExLog.d("Suppressing PlayCore: $className")
                            // Swallow the call — return without doing anything
                            callFrame.result = null
                        }
                    } catch (e: Exception) {
                        // Swallow — don't let our hook crash the game
                    }
                }

                override fun afterCall(callFrame: Pine.CallFrame) {
                    if (callFrame.hasThrowable()) {
                        val throwable = callFrame.throwable
                        if (throwable is IllegalArgumentException &&
                            throwable.message?.contains("does not exist") == true) {
                            BepInExLog.d("Clearing PlayCore exception: ${throwable.message}")
                            // Clear the exception so the game doesn't crash
                            callFrame.setResult(null)
                        }
                    }
                }
            })

            BepInExLog.i("PackageManager hooks installed on ${pmClass.name}")
        } catch (e: Exception) {
            BepInExLog.e("Failed to install PackageManager hooks", e)
        }
    }
}
