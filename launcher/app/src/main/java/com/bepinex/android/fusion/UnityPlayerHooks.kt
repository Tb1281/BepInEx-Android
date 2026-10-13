package com.bepinex.android.fusion

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.bepinex.android.BepInExLog
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch

/**
 * Pine hooks on UnityPlayer constructor(s).
 *
 * The hook:
 *   1. Wraps the Activity context with [GameContextWrapper] to redirect
 *      filesDir, cacheDir, nativeLibraryDir, SharedPreferences
 *   2. Sets nativeLibraryDir to "" to prevent the game from finding its own libs
 *   3. Shows a loading overlay while hooks are being installed
 *
 * ## This hook is only the storage half
 *
 * The wrapper goes into the `UnityPlayer` **constructor argument** only, and that is the
 * storage side: filesDir, cacheDir, prefs, nativeLibraryDir.
 *
 * It is NOT the identity side. Unity hands its C# layer the **unwrapped Activity**
 * (`UnityPlayer.currentActivity`, and the activity fields assigned in `afterCall`), and
 * that is the Context a game SDK actually receives. Anything the game resolves through
 * `getPackageName()` / `getResources()` is decided there, not here -- Firebase's
 * `google_app_id` lookup is the case that hung The Tower at 0%. The identity half is
 * [GameResourceContext], installed by [InstrumentationHooks] on `attachBaseContext`.
 *
 * The split is deliberate. Storage must stay in our sandbox or hosting has no point;
 * identity must answer as the game or the game's own SDKs cannot find their
 * configuration. Do not merge the two. See `docs/responsibility.md`.
 *
 * Note [CustomContextWrapper] also overrides `getPackageName()`/`getResources()` and
 * carries a correct diagnosis in its KDoc, but nothing instantiates it. It is a trap:
 * reading it suggests the identity problem is already handled here.
 *
 * @see UnityPlayerHooks.java in FusionCore main branch
 */
object UnityPlayerHooks {

    private const val TAG = "UnityPlayerHooks"
    private const val KILL_BLOCK_WINDOW_MS = 5_000L
    private val killHookInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var killInitTimestamp = 0L

    private val unityPlayerClassNames = arrayOf(
        "com.unity3d.player.UnityPlayer",
        "com.unity3d.player.UnityPlayerForGameActivity",
        "com.unity3d.player.UnityPlayerForActivityOrService"
    )

    /**
     * Install Pine hooks on all UnityPlayer constructors that take a Context.
     *
     * @param gameContext The game's package context (from createPackageContext)
     * @param blockUnityKill Whether startup calls to UnityPlayer.kill() should be blocked
     */
    fun installHooks(gameContext: Context, blockUnityKill: Boolean = false) {
        val classLoader = gameContext.classLoader
            ?: throw IllegalStateException("Game ClassLoader is null")

        // Find UnityPlayer class
        var unityPlayerClass: Class<*>? = null
        for (className in unityPlayerClassNames) {
            try {
                unityPlayerClass = classLoader.loadClass(className)
                BepInExLog.i("Found UnityPlayer class: ${unityPlayerClass.name}")
                break
            } catch (e: ClassNotFoundException) {
                // Try next
            }
        }

        if (unityPlayerClass == null) {
            throw IllegalStateException(
                "UnityPlayer class not found. Tried: ${unityPlayerClassNames.joinToString()}")
        }

        // Find constructors that take Context as first parameter
        val constructors = unityPlayerClass.declaredConstructors.filter { ctor ->
            ctor.parameterTypes.isNotEmpty() &&
            Context::class.java.isAssignableFrom(ctor.parameterTypes[0])
        }

        if (constructors.isEmpty()) {
            throw IllegalStateException(
                "No UnityPlayer constructor taking Context found in ${unityPlayerClass.name}")
        }

        BepInExLog.i("Found ${constructors.size} UnityPlayer constructor(s) to hook")

        if (blockUnityKill) {
            installKillHook(unityPlayerClass)
        } else {
            BepInExLog.i("UnityPlayer.kill() blocking is disabled for this game")
        }

        // Find activity field on UnityPlayer (usually m_Activity)
        val activityFields = unityPlayerClass.declaredFields.filter { field ->
            Activity::class.java.isAssignableFrom(field.type)
        }

        // Hook each constructor
        for (constructor in constructors) {
            BepInExLog.i("Hooking UnityPlayer constructor: $constructor")

            Pine.hook(constructor, object : MethodHook() {
                private var activity: Activity? = null
                private var loadingOverlay: View? = null

                override fun beforeCall(callFrame: Pine.CallFrame) {
                    try {
                        val firstArg = callFrame.args[0]
                        if (firstArg !is Activity) {
                            BepInExLog.w("First arg is not Activity: ${firstArg?.javaClass?.name}")
                            return
                        }

                        activity = firstArg
                        loadingOverlay = showLoadingOverlay(firstArg, "Injecting BepInEx...")

                        // Storage redirection only. Identity (getPackageName/getResources)
                        // is GameResourceContext's job -- see this file's KDoc.
                        callFrame.args[0] = GameContextWrapper(gameContext, firstArg, firstArg)

                        BepInExLog.i("UnityPlayer constructor: context wrapped")
                    } catch (e: Exception) {
                        BepInExLog.e("Failed to wrap context in UnityPlayer hook", e)
                    }
                }

                override fun afterCall(callFrame: Pine.CallFrame) {
                    val act = activity ?: return

                    // Hide loading overlay
                    hideLoadingOverlay(act, loadingOverlay)

                    // Set activity field on UnityPlayer instance
                    for (field in activityFields) {
                        try {
                            field.isAccessible = true
                            // Double-check: can this field actually hold our Activity?
                            if (field.type.isAssignableFrom(act.javaClass)) {
                                field.set(callFrame.thisObject, act)
                                BepInExLog.i("Set UnityPlayer.${field.name} = activity")
                            } else {
                                BepInExLog.d("Skip UnityPlayer.${field.name}: type ${field.type.name} not assignable from ${act.javaClass.name}")
                            }
                        } catch (e: Exception) {
                            BepInExLog.e("Failed to set UnityPlayer.${field.name}", e)
                        }
                    }
                    // Ensure floating log overlay service is active if enabled in settings
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            if (com.bepinex.android.settings.AppSettings.isFloatingLogInGameEnabled(act)) {
                                com.bepinex.android.log.LogOverlayService.start(act, gameContext.packageName)
                                BepInExLog.i("Floating log overlay service verified for game window")
                            }
                        } catch (e: Exception) {
                            BepInExLog.e("Failed to ensure floating log overlay service", e)
                        }
                    }, 2000)
                }
            })
        }
    }

    private fun installKillHook(unityPlayerClass: Class<*>) {
        if (!killHookInstalled.compareAndSet(false, true)) {
            BepInExLog.i("UnityPlayer.kill() hook already installed")
            return
        }

        try {
            val killMethod = generateSequence(unityPlayerClass) { it.superclass }
                .mapNotNull { clazz ->
                    clazz.declaredMethods.firstOrNull { method ->
                        method.name == "kill" && method.parameterCount == 0
                    }
                }
                .firstOrNull()
                ?: throw NoSuchMethodException("kill() not found in ${unityPlayerClass.name} hierarchy")

            killMethod.isAccessible = true
            killInitTimestamp = System.currentTimeMillis()
            Pine.hook(killMethod, object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    val elapsed = System.currentTimeMillis() - killInitTimestamp
                    if (elapsed < KILL_BLOCK_WINDOW_MS) {
                        BepInExLog.i("UnityPlayer.kill() intercepted at ${elapsed}ms")
                        callFrame.result = null
                    } else {
                        BepInExLog.i("UnityPlayer.kill() allowed after ${elapsed}ms")
                    }
                }
            })
            BepInExLog.i("Hooked ${killMethod.declaringClass.name}.kill()")
        } catch (t: Throwable) {
            killHookInstalled.set(false)
            throw IllegalStateException("Failed to hook ${unityPlayerClass.name}.kill()", t)
        }
    }

    private fun showLoadingOverlay(activity: Activity, statusText: String): View? {
        var overlay: View? = null

        val createOverlay = Runnable {
            val decor = activity.window.decorView as ViewGroup

            val overlayView = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                isClickable = true
                setBackgroundColor(0x88000000.toInt())
            }

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(48, 48, 48, 48)
            }

            val progressBar = ProgressBar(activity).apply {
                isIndeterminate = true
            }

            val status = TextView(activity).apply {
                text = statusText
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, 24, 0, 0)
            }

            container.addView(progressBar)
            container.addView(status)

            val containerParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            overlayView.addView(container, containerParams)
            decor.addView(overlayView)
            overlay = overlayView
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            createOverlay.run()
        } else {
            val latch = CountDownLatch(1)
            activity.runOnUiThread {
                createOverlay.run()
                latch.countDown()
            }
            latch.await()
        }

        return overlay
    }

    private fun hideLoadingOverlay(activity: Activity, overlay: View?) {
        if (overlay == null) return
        activity.runOnUiThread {
            val parent = overlay.parent as? ViewGroup
            parent?.removeView(overlay)
        }
    }
}
