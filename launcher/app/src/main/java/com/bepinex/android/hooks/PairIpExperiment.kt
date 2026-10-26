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
    const val EXTRA_NATURAL_FACTORY = "pairip_natural_factory"
    const val EXTRA_SKIP_FACTORY = "pairip_skip_factory"
    const val EXTRA_LATE_HOOKS = "pairip_late_hooks"
    const val EXTRA_BARE = "pairip_bare"
    const val EXTRA_PRELOAD = "pairip_preload"
    const val EXTRA_PRELOAD_DELAY_MS = "pairip_preload_delay_ms"
    const val EXTRA_STOCK_LIBMAIN = "pairip_stock_libmain"
    const val EXTRA_PATCH_UNITY_INIT = "pairip_patch_unity_init"
    const val EXTRA_GAME_APPLICATION = "pairip_game_application"
    const val EXTRA_GAME_APPLICATION_PROCESS = "pairip_game_application_process"
    const val EXTRA_THREAD_DUMP = "pairip_thread_dump"
    const val EXTRA_CANCEL_JOB = "pairip_cancel_job"
    const val EXTRA_DELAY_MS = "pairip_delay_ms"
    const val EXTRA_TRACE = "pairip_trace"
    const val EXTRA_TRACE_PRE = "pairip_trace_pre"
    const val EXTRA_TRACE_CLASS = "pairip_trace_class"
    const val EXTRA_JNI_TRACE = "pairip_jni_trace"
    const val EXTRA_DUMP_VM = "pairip_dump_vm"
    const val EXTRA_FAKE_CD = "pairip_fake_cd"
    const val EXTRA_FAKE_CD_PATH = "pairip_fake_cd_path"
    const val EXTRA_PROCESS_NAME = "pairip_process_name"
    const val EXTRA_PROC_SANITIZE = "pairip_proc_sanitize"
    const val EXTRA_SYSCALL_TRACE = "pairip_syscall_trace"
    const val EXTRA_DUMP_ARTIFACTS = "pairip_dump_artifacts"
    const val EXTRA_ALLOW_NONDEBUG = "pairip_allow_nondebug_experiments"

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

    /**
     * Debug-only: let LoadedApk.createAppFactory instantiate the game's
     * app-component factory during class-loader creation, as a stock launch
     * does, instead of detaching it and calling Class.forName afterwards.
     * This gives YTw the stock framework stack while LSPlant is still
     * deferred. Combine with [stockBoundContext] so InitContextProvider sees
     * the game's LoadedApk.
     */
    @Volatile
    var naturalFactory: Boolean = false

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
     * Debug-only: run the game's own `libmain.so` from the split APK instead
     * of the launcher's Fusion-injecting replacement. The stock library loads
     * `libunity.so`/`libil2cpp.so` exactly like a normal launch, so the PairIP
     * startup programs see the same environment they do without the host.
     * Used to separate "PairIP rejects the host module set" from "PairIP
     * rejects the modified libmain/Fusion layer".
     */
    @Volatile
    var stockLibmain: Boolean = false

    /**
     * How long to wait for PairIP's delayed startup program (normally 30 s
     * after the startup program). Debug-only override; production will need
     * to either discover completion or shorten the schedule.
     */
    @Volatile
    var preloadDelayMs: Long = 33_000L

    /**
     * Debug-only: load `libunity.so` from a host-storage copy whose
     * PairIP-injected DT_INIT stub has been replaced with `ret`. The installed
     * game APK and the split APK are never modified. Used to determine whether
     * the `klB1wZSojiVvDvCN` startup program is required for Unity to boot or
     * only performs the integrity check.
     */
    @Volatile
    var patchUnityInit: Boolean = false

    /**
     * Debug-only: create and attach the game's real `Application` through its
     * `LoadedApk` before the game Activity starts, and install it as the
     * process's initial application. A stock launch runs
     * `com.pairip.application.Application.attachBaseContext`, which points
     * `VMRunner.context` at the Application and runs the signature/license
     * checks; the hosted flow otherwise keeps the launcher's Application.
     */
    @Volatile
    var gameApplication: Boolean = false

    /**
     * Debug-only: also install the game Application as the process's
     * `mInitialApplication`. Framework code then sees the game package where
     * the platform resolves the caller's package by UID, which fails while the
     * process runs under the launcher's UID. Kept separate from
     * [gameApplication] so PairIP's VMRunner context can be tested without
     * changing framework identity.
     */
    @Volatile
    var gameApplicationAsProcess: Boolean = false

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

    /** Debug-only: trace the PairIP VM append helper while klB starts. */
    @Volatile
    var trace: Boolean = false

    /**
     * Debug-only: also trace before YTw. Any libpairipcore code hook at that
     * point trips the startup program's decoy path, so this stays off unless
     * a run is explicitly testing that behaviour.
     */
    @Volatile
    var tracePre: Boolean = false

    /**
     * Debug-only: log each uncached VM class lookup with the descriptor,
     * table and decoded entry. Confirms whether klB's failing `FindClass`
     * comes from a stale hosted-mode table entry or a wrong index.
     */
    @Volatile
    var traceClass: Boolean = false

    /**
     * Debug-only: log every JNI call whose immediate caller is inside
     * `libpairipcore.so` (class/method/field lookups, arguments, results and
     * thrown exceptions). The JNIEnv table is patched in place, so the VM's
     * own module and maps checks see an unmodified process.
     */
    @Volatile
    var jniTrace: Boolean = false

    /**
     * Debug-only: hook `VMRunner.executeVM` and fill the klB program's two
     * unresolved ZIP-view literals with a mapping of the installed game's
     * central directory. Tests whether the crash is only the missing ZIP
     * buffer or whether PairIP's decoy state is deeper.
     */
    @Volatile
    var fakeCentralDirectory: Boolean = false

    /**
     * Debug-only: when [fakeCentralDirectory] is enabled, the APK whose
     * central directory is mapped into the klB image. Empty keeps the
     * installed base APK; the arm64 split is the other candidate because
     * klB receives libunity's address inside that split as argv[0].
     */
    @Volatile
    var fakeCdPath: String = ""

    /**
     * Debug-only: write the writable VM image `executeVM` builds for klB to
     * the launcher's files directory before the interpreter runs. The dump is
     * compared with the APK asset to locate loader decryption/relocation and
     * the literals klB still reads as ciphertext.
     */
    @Volatile
    var dumpVmImage: Boolean = false

    /**
     * Debug-only: rewrite the hosted process's argv0 and task name to the
     * target game's package before PairIP's first VM runs. A stock launch
     * exposes the game package through `/proc/self/status` and
     * `/proc/self/cmdline`; the launcher's `:game` suffix is a hosted-launch
     * tell that the integrity VM parses during startup.
     */
    @Volatile
    var processNameSpoof: Boolean = false

    /**
     * Debug-only: serve the PairIP VM a same-sized `/proc/<pid>/maps` with
     * host-only lines blanked. The module shield hides host ELF objects from
     * `dl_iterate_phdr`; this hides the launcher's APK, libraries and data
     * directory from the VM's own procfs reader as well.
     */
    @Volatile
    var procSanitize: Boolean = false

    /**
     * Debug-only: record the last syscalls issued through PairIP's own
     * wrapper. The klB loader reads the APK through that wrapper, so the
     * stream shows whether the repeating VM loop is waiting on I/O, a lock,
     * or a timer.
     */
    @Volatile
    var syscallTrace: Boolean = false

    /**
     * Debug-only: copy the installed game's PairIP VM assets and native
     * libraries into the launcher's private files directory so they can be
     * pulled for offline analysis. The installed APK is only read.
     */
    @Volatile
    var dumpArtifacts: Boolean = false

    fun configure(context: Context, intent: Intent) {
        val debuggable =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Experiment-only escape hatch for the non-debuggable artifact build:
        // CheckJNI is off there, which is the configuration that reproduces a
        // retail launch. Never set by production launch code.
        if (!debuggable && !intent.getBooleanExtra(EXTRA_ALLOW_NONDEBUG, false)) return
        val extras = intent.extras ?: return
        variant = extras.getString(EXTRA_VARIANT) ?: variant
        noHooks = extras.getBoolean(EXTRA_NO_HOOKS, noHooks)
        noShield = extras.getBoolean(EXTRA_NO_SHIELD, noShield)
        gadget = extras.getBoolean(EXTRA_GADGET, gadget)
        hiddenApi = extras.getBoolean(EXTRA_HIDDEN_API, hiddenApi)
        stackSpoof = extras.getBoolean(EXTRA_STACK_SPOOF, stackSpoof)
        contextSeed = extras.getBoolean(EXTRA_CONTEXT_SEED, contextSeed)
        stockBoundContext = extras.getBoolean(EXTRA_STOCK_BOUND, stockBoundContext)
        naturalFactory = extras.getBoolean(EXTRA_NATURAL_FACTORY, naturalFactory)
        skipFactory = extras.getBoolean(EXTRA_SKIP_FACTORY, skipFactory)
        lateHooks = extras.getBoolean(EXTRA_LATE_HOOKS, lateHooks)
        bare = extras.getBoolean(EXTRA_BARE, bare)
        preload = extras.getBoolean(EXTRA_PRELOAD, preload)
        preloadDelayMs = extras.getLong(EXTRA_PRELOAD_DELAY_MS, preloadDelayMs)
            .coerceAtLeast(0L)
        stockLibmain = extras.getBoolean(EXTRA_STOCK_LIBMAIN, stockLibmain)
        patchUnityInit = extras.getBoolean(EXTRA_PATCH_UNITY_INIT, patchUnityInit)
        gameApplication = extras.getBoolean(EXTRA_GAME_APPLICATION, gameApplication)
        gameApplicationAsProcess = extras.getBoolean(
            EXTRA_GAME_APPLICATION_PROCESS,
            gameApplicationAsProcess
        )
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
        trace = extras.getBoolean(EXTRA_TRACE, trace)
        tracePre = extras.getBoolean(EXTRA_TRACE_PRE, tracePre)
        traceClass = extras.getBoolean(EXTRA_TRACE_CLASS, traceClass)
        jniTrace = extras.getBoolean(EXTRA_JNI_TRACE, jniTrace)
        dumpVmImage = extras.getBoolean(EXTRA_DUMP_VM, dumpVmImage)
        fakeCentralDirectory = extras.getBoolean(EXTRA_FAKE_CD, fakeCentralDirectory)
        fakeCdPath = extras.getString(EXTRA_FAKE_CD_PATH) ?: fakeCdPath
        processNameSpoof = extras.getBoolean(EXTRA_PROCESS_NAME, processNameSpoof)
        procSanitize = extras.getBoolean(EXTRA_PROC_SANITIZE, procSanitize)
        syscallTrace = extras.getBoolean(EXTRA_SYSCALL_TRACE, syscallTrace)
        dumpArtifacts = extras.getBoolean(EXTRA_DUMP_ARTIFACTS, dumpArtifacts)
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
        append(" naturalFactory=").append(naturalFactory)
        append(" skipFactory=").append(skipFactory)
        append(" lateHooks=").append(lateHooks)
        append(" bare=").append(bare)
        append(" preload=").append(preload)
        append(" preloadDelayMs=").append(preloadDelayMs)
        append(" stockLibmain=").append(stockLibmain)
        append(" patchUnityInit=").append(patchUnityInit)
        append(" gameApplication=").append(gameApplication)
        append(" gameApplicationAsProcess=").append(gameApplicationAsProcess)
        append(" threadDump=").append(threadDump)
        append(" cancelJob=").append(cancelJob)
        append(" delayMs=").append(delayMs)
        append(" trace=").append(trace)
        append(" tracePre=").append(tracePre)
        append(" traceClass=").append(traceClass)
        append(" jniTrace=").append(jniTrace)
        append(" dumpVmImage=").append(dumpVmImage)
        append(" fakeCentralDirectory=").append(fakeCentralDirectory)
        append(" fakeCdPath=").append(fakeCdPath)
        append(" processNameSpoof=").append(processNameSpoof)
        append(" procSanitize=").append(procSanitize)
        append(" syscallTrace=").append(syscallTrace)
        append(" dumpArtifacts=").append(dumpArtifacts)
    }
}
