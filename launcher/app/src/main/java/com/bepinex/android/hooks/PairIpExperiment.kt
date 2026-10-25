package com.bepinex.android.hooks

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Debug-only switches for the PairIP hosting investigation.
 *
 * The defaults reproduce the production hosting path. Extras are honored only
 * when the launcher APK itself is debuggable, so a release build cannot be
 * reconfigured from an intent.
 *
 * Every layer is independent on purpose: the isolation matrix needs to tell
 * whether PairIP reacts to the hook engine, the module shield, the gadget, the
 * hidden-API policy, stack spoofing, or context seeding. The defaults stay
 * enabled so a normal launch is unchanged.
 */
object PairIpExperiment {
    const val EXTRA_VARIANT = "pairip_variant"
    const val EXTRA_NO_HOOKS = "pairip_no_hooks"
    const val EXTRA_NO_SHIELD = "pairip_no_shield"
    const val EXTRA_GADGET = "pairip_gadget"
    const val EXTRA_HIDDEN_API = "pairip_hidden_api"
    const val EXTRA_STACK_SPOOF = "pairip_stack_spoof"
    const val EXTRA_CONTEXT_SEED = "pairip_context_seed"
    const val EXTRA_STOCK_BOUND = "pairip_stock_bound"
    const val EXTRA_SKIP_FACTORY = "pairip_skip_factory"
    const val EXTRA_LATE_HOOKS = "pairip_late_hooks"
    const val EXTRA_BARE = "pairip_bare"
    const val EXTRA_PRELOAD = "pairip_preload"
    const val EXTRA_PRELOAD_DELAY_MS = "pairip_preload_delay_ms"
    const val EXTRA_THREAD_DUMP = "pairip_thread_dump"
    const val EXTRA_CANCEL_JOB = "pairip_cancel_job"
    const val EXTRA_DELAY_MS = "pairip_delay_ms"

    /**
     * Marker passed to the game's `NativeLoader.load` to ask our libmain for
     * the preload-only path. The real call from UnityPlayer passes a native
     * library directory, so this can never collide with a path.
     */
    const val PRELOAD_MARKER = "bepinex-preload"

    @Volatile
    var variant: String = "default"

    @Volatile
    var noHooks: Boolean = false

    @Volatile
    var noShield: Boolean = false

    @Volatile
    var gadget: Boolean = false

    @Volatile
    var hiddenApi: Boolean = true

    @Volatile
    var stackSpoof: Boolean = true

    @Volatile
    var contextSeed: Boolean = true

    /**
     * Debug-only: let PairIP build its own context exactly as it does in a
     * stock launch. The host temporarily points ActivityThread's
     * mBoundApplication.info at the game's LoadedApk, leaves VMRunner.context
     * null, and thereby runs InitContextProvider (including PairIP's
     * Instrumentation replacement) instead of pre-seeding a Context.
     */
    @Volatile
    var stockBoundContext: Boolean = false

    @Volatile
    var skipFactory: Boolean = false

    /**
     * Debug-only: run the game's app-component factory (and therefore PairIP's
     * startup VM programs) before the LSPlant hook engine is initialized,
     * then install the hosting hooks. PairIP's per-opcode integrity checks
     * fail when LSPlant/Dobby have already modified the process.
     */
    @Volatile
    var lateHooks: Boolean = false

    /**
     * Debug-only: do not initialize the hook engine, Fusion, or the module
     * shield. Used to tell whether PairIP's delayed startup check reacts to
     * LSPlant itself or to the hosted context. The game activity is launched
     * directly, so it runs in its own process exactly as a stock launch.
     */
    @Volatile
    var bare: Boolean = false

    /**
     * Debug-only: run every PairIP startup program that normally runs during
     * process startup **before** initializing LSPlant. The factory program
     * runs first (via [lateHooks]), then `libmain` is loaded into the game's
     * linker namespace and dlopens `libunity.so`/`libil2cpp.so` so their
     * DT_INIT programs run. PairIP's delayed program is one-shot at T+30 s,
     * so this path waits for it before installing the hosting hooks.
     */
    @Volatile
    var preload: Boolean = false

    /**
     * How long to wait for PairIP's delayed startup program (normally 30 s
     * after the startup program). Debug-only override; production will need
     * to either discover completion or shorten the schedule.
     */
    @Volatile
    var preloadDelayMs: Long = 33_000L

    /**
     * Debug-only: log the Java threads and `VMRunner.getJob()` after PairIP's
     * startup program. Used to identify the scheduler behind the 30-second
     * delayed program.
     */
    @Volatile
    var threadDump: Boolean = false

    /**
     * Debug-only: cancel whatever `VMRunner.getJob()` returns after the
     * startup program, then check whether the delayed program stops.
     */
    @Volatile
    var cancelJob: Boolean = false

    @Volatile
    var delayMs: Long = 0L

    fun configure(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val extras = intent.extras ?: return
        variant = extras.getString(EXTRA_VARIANT) ?: variant
        noHooks = extras.getBoolean(EXTRA_NO_HOOKS, noHooks)
        noShield = extras.getBoolean(EXTRA_NO_SHIELD, noShield)
        gadget = extras.getBoolean(EXTRA_GADGET, gadget)
        hiddenApi = extras.getBoolean(EXTRA_HIDDEN_API, hiddenApi)
        stackSpoof = extras.getBoolean(EXTRA_STACK_SPOOF, stackSpoof)
        contextSeed = extras.getBoolean(EXTRA_CONTEXT_SEED, contextSeed)
        stockBoundContext = extras.getBoolean(EXTRA_STOCK_BOUND, stockBoundContext)
        skipFactory = extras.getBoolean(EXTRA_SKIP_FACTORY, skipFactory)
        lateHooks = extras.getBoolean(EXTRA_LATE_HOOKS, lateHooks)
        bare = extras.getBoolean(EXTRA_BARE, bare)
        preload = extras.getBoolean(EXTRA_PRELOAD, preload)
        preloadDelayMs = extras.getLong(EXTRA_PRELOAD_DELAY_MS, preloadDelayMs)
            .coerceAtLeast(0L)
        threadDump = extras.getBoolean(EXTRA_THREAD_DUMP, threadDump)
        cancelJob = extras.getBoolean(EXTRA_CANCEL_JOB, cancelJob)
        if (preload) {
            // The factory program must run before LSPlant, and it must run
            // through the stock late-hooks path.
            lateHooks = true
        }
        if (bare) {
            noHooks = true
            noShield = true
        }
        delayMs = extras.getLong(EXTRA_DELAY_MS, delayMs).coerceAtLeast(0L)
    }

    fun describe(): String = buildString {
        append("variant=").append(variant)
        append(" noHooks=").append(noHooks)
        append(" noShield=").append(noShield)
        append(" gadget=").append(gadget)
        append(" hiddenApi=").append(hiddenApi && !noHooks)
        append(" stackSpoof=").append(stackSpoof && !noHooks)
        append(" contextSeed=").append(contextSeed || skipFactory)
        append(" stockBound=").append(stockBoundContext)
        append(" skipFactory=").append(skipFactory)
        append(" lateHooks=").append(lateHooks)
        append(" bare=").append(bare)
        append(" preload=").append(preload)
        append(" preloadDelayMs=").append(preloadDelayMs)
        append(" threadDump=").append(threadDump)
        append(" cancelJob=").append(cancelJob)
        append(" delayMs=").append(delayMs)
    }
}
