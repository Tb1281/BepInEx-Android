package com.bepinex.android.fusion

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import com.bepinex.android.BepInExLog
import com.bepinex.android.StubActivity
import java.lang.reflect.Method
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook

/**
 * Hooks [Instrumentation.execStartActivity] and [Instrumentation.newActivity] so
 * the game's own activities are instantiated **in this process** rather than in the
 * game's.
 *
 * ## Why the hop exists at all
 *
 * The game is installed as its own package. `com.oninou.revolution/com.unity3d.player.UnityPlayerActivity`
 * is declared in *its* manifest, so an explicit intent to it resolves against the
 * game's package and AMS would start it in the game's own process -- where none of
 * this exists: not libfusion, not the CoreCLR host, not the BepInEx chainloader.
 *
 * Redirecting through [StubActivity] puts AMS in front of an activity this APK does
 * declare, so the activity is created here, and [Instrumentation.newActivity] then
 * restores the real target so the instance is the game's class inside our process.
 * The redirect is a process-selection mechanism, not an error recovery.
 *
 * ## Scope: the component's package decides, and nothing else
 *
 * Only activities whose **component package** is the launched game's package are
 * redirected. The component package is the criterion rather than the class name,
 * because a Unity launcher activity is `com.unity3d.player.UnityPlayerActivity`:
 * the class lives in Unity's package while the component package is the game's.
 * Testing the class name would miss the one activity that most needs redirecting.
 *
 * Everything else passes through untouched. A `startActivityForResult` whose callee
 * does not come back leaves the caller's `await` suspended forever, and substituting
 * a stub for such a callee produces exactly that with no exception anywhere.
 *
 * ## Measured on device: what the missing scope cost
 *
 * Google Play services sign-in, captured on a Galaxy S23 / Android 16 with
 * .NET 10.0.12, before the package check existed:
 *
 *   execStartActivity: intercepted unregistered activity:
 *     com.google.android.gms.auth.api.signin.internal.SignInHubActivity
 *
 * The title screen sat on its loading spinner indefinitely. `StubActivity` is
 * declared here and therefore always startable, so the game's start call succeeded
 * and nothing ever delivered a result back. The symptom was a hang with no
 * exception, and it was not specific to the .NET runtime -- it reproduced identically
 * on .NET 11 and .NET 10.
 *
 * After the check, the same call is left alone and the failure becomes catchable,
 * which is what the game needs:
 *
 *   execStartActivity: leaving com.bepinex.android/com.google.android.gms...SignInHubActivity alone
 *   ActivityTaskManager: START ... result code=-92
 *   SignInFragment: Unable to find explicit activity class {...}
 *   Calling onResult for callback. result: Status{statusCode=INTERNAL_ERROR}
 *   GoogleSignInManager:OnAuthenticationFinished(Task`1)
 *   [SERVER][ERROR] Exception: Google.GoogleSignIn+SignInException at NakamaManager.GoogleAuth ()
 *
 * Note the component package in that log: `com.bepinex.android`, ours. Play services
 * builds its sign-in component from `context.getPackageName()`, and the game's
 * Android Activity lives in this process, so it reports our package and we do not
 * declare the class.
 *
 * **Google Sign-In cannot succeed under this architecture regardless of this hook.**
 * It validates the calling application's signing certificate against a registered
 * OAuth client, and this launcher's signing key is not the game's. A plugin that
 * replaces the sign-in flow is therefore working around an impossibility, not
 * around a launcher defect. Do not treat the game reaching its title screen as
 * "sign-in works".
 *
 * @see InstrumentationHooks.java in FusionCore
 */
object InstrumentationHooks {

    /**
     * Marks an intent that has already been redirected, by this hook or by
     * [com.bepinex.android.BootstrapActivity] for the first game activity.
     *
     * This is a re-entrancy marker, not a policy flag. It records what has already
     * happened to the intent; whether an intent *should* be redirected is decided
     * separately, by comparing the component's package against [gamePackage].
     */
    const val EXTRA_REDIRECTED_ACTIVITY = "bepinex.redirected_activity"
    const val EXTRA_ORIGINAL_INTENT = "bepinex.original_intent"
    const val EXTRA_TARGET_ORIENTATION = "bepinex.target_orientation"

    /**
     * The launched game's package. Only activities in this package are redirected.
     * Null until [install] runs, and a redirect is refused rather than guessed while
     * it is null: redirecting everything is the bug this field exists to bound.
     */
    @Volatile
    private var gamePackage: String? = null

    @Volatile
    var areHooksInstalled = false
        private set

    fun install(gamePackage: String) {
        require(gamePackage.isNotBlank()) { "gamePackage must not be blank" }
        // Assigned before the idempotency check so the field can never be read as
        // null by a hook that is already live.
        this.gamePackage = gamePackage
        if (areHooksInstalled) {
            BepInExLog.d("Instrumentation hooks already installed")
            return
        }
        BepInExLog.i("Instrumentation hooks will redirect only $gamePackage activities")

        try {
            hookAllMethodsByName(Instrumentation::class.java, "execStartActivity", object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    handleExecStartBeforeCall(callFrame)
                }
            })

            hookAllMethodsByName(Instrumentation::class.java, "newActivity", object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    handleNewActivityBeforeCall(callFrame)
                }
            })

            hookActivityAttach()
            hookActivityOnCreate()
            areHooksInstalled = true
            BepInExLog.i("Instrumentation hooks installed successfully")
        } catch (e: Exception) {
            BepInExLog.e("Failed to install Instrumentation hooks", e)
        }
    }

    /**
     * Hook `Activity.attach(Context, ActivityClientRecord)` to re-point the hosted
     * Activity's identity at the game's own APK.
     *
     * `attach` is the single point where an Activity's base Context is established, and
     * it runs before `onCreate`. Rewriting the base Context here fixes every later
     * reader at once -- including Unity's C# layer, which is handed the raw Activity via
     * `UnityPlayer.currentActivity` and therefore never sees the Context wrapper that
     * [UnityPlayerHooks] substitutes for the `UnityPlayer` constructor argument.
     *
     * Without this, `Activity.getPackageName()` answers `"com.bepinex.android"` and
     * `getResources()` answers with this APK's resource table. Any game SDK that
     * resolves configuration by `getIdentifier(name, "string", getPackageName())` then
     * finds nothing -- even when the game ships the value in its own APK. The Tower's
     * Firebase Crashlytics does exactly that, throws `InitializationException` out of
     * `GameInitializer.Start`, and the splash hangs at 0% with no crash.
     *
     * `attachBaseContext` is used rather than `Activity.attach`. `attach` is a
     * hidden/system method that is not reflectable on recent API levels -- measured on
     * Android 17 (SDK 37), `declaredMethods` does not expose it, so hooking it silently
     * finds nothing. `attachBaseContext` is public API, and `Activity.attach` calls it
     * with the very same Context before `onCreate` runs, so it is the equivalent hook
     * point that is actually reachable.
     *
     * The Context is located by type rather than by position, since the signature
     * differs across API levels.
     */
    private fun hookActivityAttach() {
        val gamePkg = gamePackage
        if (gamePkg == null) {
            BepInExLog.e("Skipping Activity.attachBaseContext hook: game package unknown")
            return
        }

        val gameContext = gameContextRef
        if (gameContext == null) {
            BepInExLog.e("Skipping Activity.attachBaseContext hook: no game context to read resources from")
            return
        }

        // ContextThemeWrapper declares it, Activity overrides it. Hooking only the
        // declaring class would miss Activity's override, so walk the hierarchy and hook
        // each declaration that takes a single Context.
        val methods = LinkedHashSet<Method>()
        var clazz: Class<*>? = Activity::class.java
        while (clazz != null && clazz != ContextWrapper::class.java) {
            clazz.declaredMethods
                .filter { it.name == "attachBaseContext" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Context::class.java }
                .forEach { methods.add(it) }
            clazz = clazz.superclass
        }
        if (methods.isEmpty()) {
            BepInExLog.e("Activity.attachBaseContext(Context) not found; game resources will not resolve")
            return
        }

        val storageContext = storageContextRef
        if (storageContext == null) {
            BepInExLog.e("Skipping Activity.attachBaseContext hook: no storage context")
            return
        }

        for (method in methods) {
            try {
                method.isAccessible = true
                Pine.hook(method, object : MethodHook() {
                    override fun beforeCall(callFrame: Pine.CallFrame) {
                        val activity = callFrame.thisObject as? Activity
                        if (activity != null) {
                            GameResourceContext.wrapApplication(activity.application, gameContext)
                        }
                        val args = callFrame.args ?: return
                        val existing = args.firstOrNull() as? Context ?: return
                        if (existing is GameResourceContext) return
                        args[0] = GameResourceContext(existing, gameContext, storageContext)
                        BepInExLog.d("attachBaseContext: base context re-pointed at $gamePkg resources")
                    }
                })
                BepInExLog.i("Hooking ${method.declaringClass.name}.${method.name}")
            } catch (e: Exception) {
                BepInExLog.e("Failed to hook ${method.declaringClass.name}.${method.name}", e)
            }
        }
    }

    /**
     * The game's package Context, needed by [hookActivityAttach] to reach the game's
     * resource table. Set by [BootstrapActivity] before [install].
     */
    @Volatile
    var gameContextRef: Context? = null

    /**
     * The launcher's own Context, supplying the writable storage paths that
     * [GameResourceContext] repoints `dataDir` at. Set by `BootstrapActivity` before
     * [install].
     */
    @Volatile
    var storageContextRef: Context? = null

    private fun hookAllMethodsByName(clazz: Class<*>, methodName: String, hook: MethodHook) {
        try {
            for (method in clazz.declaredMethods) {
                if (method.name == methodName) {
                    Pine.hook(method, hook)
                }
            }
        } catch (e: SecurityException) {
            BepInExLog.e("Failed to hook $methodName on ${clazz.name}", e)
        }
    }

    private fun hookActivityOnCreate() {
        val orientationHook = object : MethodHook() {
            override fun beforeCall(callFrame: Pine.CallFrame) {
                val activity = callFrame.thisObject as? Activity ?: return
                applyTargetOrientation(activity)
            }
        }
        try {
            Pine.hook(Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java), orientationHook)
            Pine.hook(Activity::class.java.getDeclaredMethod("onResume"), orientationHook)
        } catch (e: Exception) {
            BepInExLog.e("Failed to hook Activity lifecycle for orientation: ${e.message}", e)
        }
    }

    private fun applyTargetOrientation(activity: Activity) {
        try {
            val intent = activity.intent ?: return
            val orientation = readTargetOrientation(intent)
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
            activity.requestedOrientation = orientation
            BepInExLog.i("Applied target orientation $orientation to ${activity.javaClass.name}")
        } catch (e: Exception) {
            BepInExLog.e("Failed to apply target orientation", e)
        }
    }

    private fun readTargetOrientation(intent: Intent): Int {
        val orientation = intent.getIntExtra(
            EXTRA_TARGET_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        )
        if (orientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return orientation
        return resolveOriginalIntent(intent)?.getIntExtra(
            EXTRA_TARGET_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ) ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun handleExecStartBeforeCall(callFrame: Pine.CallFrame) {
        try {
            val args = callFrame.args ?: return
            val intentIdx = args.indexOfFirst { it is Intent }
            if (intentIdx < 0) {
                BepInExLog.e("No intent found in execStartActivity")
                return
            }

            val intent = args[intentIdx] as? Intent
            if (intent?.component == null) {
                BepInExLog.e("Intent or Intent component was null")
                return
            }

            // Re-entrancy guard: this intent was already redirected, here or by
            // BootstrapActivity for the first game activity. Wrapping it twice would
            // nest a stub inside a stub and lose the real target.
            if (alreadyRedirected(intent)) return

            val target = intent.component!!
            val gamePkg = gamePackage
            if (gamePkg == null) {
                BepInExLog.e(
                    "refusing to redirect ${target.flattenToString()}: the game package is unknown. " +
                        "Redirecting without it would capture every activity in the process."
                )
                return
            }

            // The policy: only this game's own activities belong in this process. Anything
            // else -- Play Services, an ad SDK, WebView -- is someone else's callee, and a
            // stub substituted for it never returns a result, so its caller waits forever.
            if (target.packageName != gamePkg) {
                BepInExLog.d(
                    "execStartActivity: leaving ${target.flattenToString()} alone " +
                        "(not in $gamePkg)"
                )
                return
            }

            args[intentIdx] = redirectVia(intent)
            BepInExLog.d("execStartActivity: redirected game activity ${target.flattenToString()}")
        } catch (e: Exception) {
            BepInExLog.e("Error in execStartActivity beforeCall", e)
        }
    }

    private fun handleNewActivityBeforeCall(callFrame: Pine.CallFrame) {
        try {
            val args = callFrame.args ?: return
            var intentIdx = -1
            var strIdx = -1
            for (i in args.indices) {
                when (args[i]) {
                    is Intent -> intentIdx = i
                    is String -> strIdx = i
                }
            }
            if (intentIdx < 0 || strIdx < 0) {
                BepInExLog.e("Intent or String not found in newActivity")
                return
            }

            val intent = args[intentIdx] as Intent
            if (!alreadyRedirected(intent)) return

            val original = resolveOriginalIntent(intent)
            if (original?.component != null) {
                args[intentIdx] = original
                args[strIdx] = original.component!!.className
                BepInExLog.d("newActivity: restored ${original.component!!.className}")
            } else {
                BepInExLog.e("Failed to resolve original intent")
            }
        } catch (e: Exception) {
            BepInExLog.e("Error in newActivity beforeCall", e)
        }
    }

    private fun resolveOriginalIntent(currentIntent: Intent): Intent? {
        return try {
            currentIntent.setExtrasClassLoader(InstrumentationHooks::class.java.classLoader)
            @Suppress("DEPRECATION")
            val originalIntent = currentIntent.getParcelableExtra<Intent>(EXTRA_ORIGINAL_INTENT)
            if (originalIntent?.component != null) {
                BepInExLog.d("Resolved original intent for ${originalIntent.component!!.className}")
                originalIntent
            } else {
                null
            }
        } catch (e: Exception) {
            BepInExLog.e("Error resolving original intent", e)
            null
        }
    }

    /**
     * Wrap [intent] so AMS resolves [StubActivity] -- which this APK declares -- while
     * the real target is carried in [EXTRA_ORIGINAL_INTENT] for [Instrumentation.newActivity]
     * to restore. The stub is a decoy for AMS; it is never the activity the game gets.
     */
    private fun redirectVia(intent: Intent): Intent {
        val newIntent = Intent(intent)
        newIntent.putExtra(EXTRA_REDIRECTED_ACTIVITY, true)
        newIntent.putExtra(EXTRA_ORIGINAL_INTENT, intent)
        val hostPackage = storageContextRef?.packageName ?: "com.bepinex.android"
        newIntent.component = ComponentName(hostPackage, StubActivity::class.java.name)
        return newIntent
    }

    /**
     * Whether this intent has already been through [redirectVia]. A re-entrancy
     * guard, nothing more -- it says what already happened, not what should happen.
     */
    private fun alreadyRedirected(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(EXTRA_REDIRECTED_ACTIVITY, false) == true
    }
}
