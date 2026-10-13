package com.bepinex.android.fusion

import com.bepinex.android.BepInExLog
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Method

/**
 * Pine hooks on ClassLoader.loadClass() for bidirectional class loading.
 *
 * This is necessary because:
 *   1. Our code needs to load game Activity classes (from game DEX)
 *   2. Game code may need classes from our DEX (UnityPlayer stubs etc.)
 *
 * The hook wraps loadClass() on BOTH classloaders to enable cross-loading
 * when a class is not found in the default loader.
 *
 * A beforeCall normalizer rewrites JNI-style `com/foo/Bar` names to binary
 * `com.foo.Bar` form. Firebase C++ (e.g. v13.12.0 `FindClassInFiles`, Android
 * path) hands its JNI names straight to `DexClassLoader.loadClass()`, whose
 * contract requires binary names; the desktop path converts, the Android one
 * does not. Unnormalized, a class that IS present (e.g. CppTransactionHandler
 * in the runtime-extracted `database_resources_lib.jar`) still throws, Firebase
 * treats embedded-class init as failed, and `Database::GetInstance` later
 * segfaults in `DeleteInternal`. Rewriting only turns guaranteed failures
 * into possible successes; dotted names pass through untouched.
 *
 * @see ClassLoaderHooks.kt in NextBep BepInEx.Android.Launcher (no equivalent
 * exists in All-Of-Us FusionCore)
 */
object ClassLoaderHooks {

    private const val TAG = "ClassLoaderHooks"

    /**
     * Install the bidirectional loadClass hook.
     *
     * @param gameClassLoader The game's ClassLoader (from createPackageContext with CONTEXT_INCLUDE_CODE)
     */
    fun installHooks(gameClassLoader: ClassLoader) {
        val loadClassMethod = findLoadClassMethod()
            ?: throw IllegalStateException("Cannot find ClassLoader.loadClass method for hooking")

        val myClassLoader = ClassLoaderHooks::class.java.classLoader
            ?: throw IllegalStateException("Our ClassLoader is null")

        BepInExLog.i("Hooking loadClass: ${loadClassMethod.declaringClass.name}.${loadClassMethod.name}")
        BepInExLog.i("  Our loader: ${myClassLoader.javaClass.name}")
        BepInExLog.i("  Game loader: ${gameClassLoader.javaClass.name}")

        Pine.hook(loadClassMethod, object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                // loadClass() takes binary names; a JNI-style name can never
                // resolve and only obscures the real lookup. See class KDoc.
                val args = callFrame.args ?: return
                if (args.isNotEmpty()) {
                    val name = args[0] as? String
                    if (name != null && name.indexOf('/') >= 0) {
                        val dotted = name.replace('/', '.')
                        args[0] = dotted
                        BepInExLog.d("loadClass: normalized JNI name $name → $dotted")
                    }
                }
            }

            override fun afterCall(callFrame: Pine.CallFrame) {
                try {
                    // Only attempt fallback if class was not found
                    if (callFrame.throwable !is ClassNotFoundException) return

                    val className = callFrame.args[0] as String
                    @Suppress("UNUSED_VARIABLE")
                    val resolve = callFrame.args[1] as Boolean

                    // If game loader failed, try our loader
                    if (callFrame.thisObject == gameClassLoader) {
                        try {
                            val myClass = callFrame.invokeOriginalMethod(
                                myClassLoader, className, resolve) as Class<*>
                            callFrame.result = myClass
                            BepInExLog.d("loadClass fallback: $className → OUR loader")
                            return
                        } catch (e: Exception) {
                            // Not in our loader either — fall through to the
                            // Firebase bridge jars below before giving up.
                        }
                    }

                    // If our loader failed, try game loader
                    if (callFrame.thisObject == myClassLoader) {
                        try {
                            val gameClass = callFrame.invokeOriginalMethod(
                                gameClassLoader, className, resolve) as Class<*>
                            callFrame.result = gameClass
                            BepInExLog.d("loadClass fallback: $className → GAME loader")
                            return
                        } catch (e: Exception) {
                            // Not in game loader either — fall through to the
                            // Firebase bridge jars below before giving up.
                        }
                    }

                    // Last resort: Firebase's runtime-extracted bridge jars.
                    // Applies to any loader (including Firebase's own private
                    // DexClassLoaders over those jars): game-dex lookups for
                    // these classes (e.g. CppTransactionHandler) fail even
                    // though the class is on disk; see FirebaseBridgeLoader.
                    // Only reached after a genuine miss, so normal resolution
                    // order is unchanged.
                    try {
                        val bridged =
                            FirebaseBridgeLoader.loadClass(className)
                        if (bridged != null) {
                            callFrame.result = bridged
                            BepInExLog.d("loadClass fallback: $className → FIREBASE jar")
                        }
                    } catch (e: Exception) {
                        BepInExLog.e("Unexpected error in Firebase bridge fallback", e)
                    }
                } catch (e: Exception) {
                    BepInExLog.e("Unexpected error in loadClass hook", e)
                }
            }
        })
    }

    /**
     * Find ClassLoader.loadClass(String, boolean) via reflection.
     */
    private fun findLoadClassMethod(): Method? {
        var clazz: Class<*>? = ClassLoaderHooks::class.java.classLoader?.javaClass

        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod("loadClass", String::class.java, Boolean::class.javaPrimitiveType!!)
                method.isAccessible = true
                BepInExLog.i("Found loadClass in ${clazz.name}")
                return method
            } catch (e: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }

        return null
    }
}
