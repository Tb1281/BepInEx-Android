package com.bepinex.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager

/**
 * Manages screen dim-instead-of-off behavior for game activities.
 *
 * When attached to a game Activity:
 * - Keeps the screen on ([WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON])
 * - After the system's screen-off timeout with no user interaction:
 *   - If **not charging**: dims to minimum brightness
 *   - If **charging**: keeps current brightness (screen stays on normally)
 * - Automatically pauses timers when activity goes to background (onPause/onStop)
 * - Restores normal brightness and resets timeout when activity resumes (onResume)
 * - On user interaction: restores original brightness and resets the timer
 */
class ScreenDimManager : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var dimRunnable: Runnable? = null
    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var isDimmed = false
    private var isPaused = false
    private var attachedActivity: Activity? = null

    companion object {
        /**
         * Near-minimum brightness value.
         * Using a small positive value instead of 0 to avoid fully-black
         * screens on devices that interpret 0 as "use system default".
         */
        private const val MIN_BRIGHTNESS = 0.004f
        private const val DEFAULT_TIMEOUT_MS = 30_000L

        @Volatile
        var instance: ScreenDimManager? = null
            private set

        /**
         * Creates a new [ScreenDimManager] and attaches it to the given activity.
         * Any previously attached instance is detached first.
         */
        fun createAndAttach(activity: Activity) {
            instance?.detach()
            val manager = ScreenDimManager()
            instance = manager
            manager.attach(activity)
        }
    }

    private fun attach(activity: Activity) {
        attachedActivity = activity
        isPaused = false

        // Register lifecycle callbacks so we know when the app goes to background / foreground
        try {
            activity.application.registerActivityLifecycleCallbacks(this)
        } catch (e: Exception) {
            BepInExLog.w("Failed to register ActivityLifecycleCallbacks: ${e.message}")
        }

        // Keep screen on while in foreground
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Capture initial brightness (only if not already dimmed)
        val currentBrightness = activity.window.attributes.screenBrightness
        if (currentBrightness > MIN_BRIGHTNESS || currentBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            originalBrightness = currentBrightness
        }

        // Start idle timer
        scheduleIdleDim(activity)

        BepInExLog.i("ScreenDimManager attached to ${activity.javaClass.simpleName}")
    }

    /**
     * Detaches from the current activity and restores original state.
     */
    fun detach() {
        dimRunnable?.let { handler.removeCallbacks(it) }
        dimRunnable = null

        attachedActivity?.let { activity ->
            try {
                activity.application.unregisterActivityLifecycleCallbacks(this)
            } catch (e: Exception) {
                // Ignore
            }

            if (!activity.isFinishing && !activity.isDestroyed) {
                restoreBrightness(activity)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        attachedActivity = null
        instance = null
        BepInExLog.i("ScreenDimManager detached")
    }

    /**
     * Called from Pine hook on [Activity.onUserInteraction].
     * Restores brightness if dimmed and resets the idle timer.
     */
    fun onUserInteraction() {
        if (isPaused) return
        val activity = attachedActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        if (isDimmed) {
            restoreBrightness(activity)
        }
        scheduleIdleDim(activity)
    }

    // Lifecycle callbacks to handle background/foreground transitions

    override fun onActivityResumed(activity: Activity) {
        if (activity == attachedActivity) {
            isPaused = false
            BepInExLog.i("ScreenDimManager: Activity resumed, restoring brightness and resetting timer")
            restoreBrightness(activity)
            scheduleIdleDim(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity == attachedActivity) {
            isPaused = true
            BepInExLog.i("ScreenDimManager: Activity paused (backgrounded), cancelling dim timer")
            dimRunnable?.let { handler.removeCallbacks(it) }
            dimRunnable = null
            restoreBrightness(activity)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity == attachedActivity) {
            isPaused = true
            dimRunnable?.let { handler.removeCallbacks(it) }
            dimRunnable = null
            restoreBrightness(activity)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity == attachedActivity) {
            detach()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private fun scheduleIdleDim(activity: Activity) {
        dimRunnable?.let { handler.removeCallbacks(it) }
        if (isPaused) return

        val timeout = getScreenTimeout(activity)
        // A non-positive system timeout means "never turn off the screen". There is
        // no idle deadline to dim against, so don't schedule (and never post with a
        // negative delay, which Handler treats as "run immediately").
        if (timeout <= 0L) return

        val runnable = Runnable {
            val act = attachedActivity ?: return@Runnable
            if (act.isFinishing || act.isDestroyed || isPaused) return@Runnable

            if (!isCharging(act)) {
                dimScreen(act)
            } else {
                // Keep the screen on while charging without dimming, but re-arm so
                // an unplug later still gets an idle deadline.
                scheduleIdleDim(act)
            }
        }
        dimRunnable = runnable
        handler.postDelayed(runnable, timeout)
    }

    private fun dimScreen(activity: Activity) {
        if (isDimmed || isPaused) return
        val params = activity.window.attributes

        // Save current brightness only if it is not already dimmed
        if (params.screenBrightness > MIN_BRIGHTNESS || params.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            originalBrightness = params.screenBrightness
        }
        params.screenBrightness = MIN_BRIGHTNESS
        activity.window.attributes = params
        isDimmed = true
        BepInExLog.i("Screen dimmed to minimum brightness (idle timeout, not charging)")
    }

    private fun restoreBrightness(activity: Activity) {
        val current = activity.window.attributes.screenBrightness
        if (!isDimmed && current != MIN_BRIGHTNESS) return
        val params = activity.window.attributes
        params.screenBrightness = originalBrightness
        activity.window.attributes = params
        isDimmed = false
        BepInExLog.i("Screen brightness restored to normal")
    }

    private fun getScreenTimeout(context: Context): Long {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT
            ).toLong()
        } catch (e: Exception) {
            DEFAULT_TIMEOUT_MS
        }
    }

    private fun isCharging(context: Context): Boolean {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.isCharging
        } catch (e: Exception) {
            false
        }
    }
}
