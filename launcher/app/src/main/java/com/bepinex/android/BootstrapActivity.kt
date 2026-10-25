package com.bepinex.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bepinex.android.fusion.*
import com.bepinex.android.log.LogOverlayService
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.hooks.HookEngine
import com.bepinex.android.hooks.CallFrame
import com.bepinex.android.hooks.MethodHook
import com.bepinex.android.hooks.PairIpExperiment
import com.bepinex.android.hooks.PairIpShield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bootstrap activity that injects BepInEx into the target game process
 * via LSPlant hooks, custom libmain.so/libfusion.so, and CoreCLR bootstrap.
 */
class BootstrapActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity"
        const val EXTRA_ACTIVE_MODPACKS = "active_modpacks" // null/empty = vanilla (List<String>)
        const val EXTRA_ACTIVE_MODPACK = "active_modpack"   // single string backwards compatibility
        const val EXTRA_BLOCK_UNITY_KILL = "block_unity_kill"
        // Game-behavior flags. The :game process must never open the default
        // process's DataStore file (not multi-process safe), so every setting
        // the game needs travels in the launch Intent; MainActivity always sets
        // them, and the fallbacks below are only for defensive direct starts.
        const val EXTRA_SCREEN_DIM_ENABLED = "screen_dim"
        const val EXTRA_FLOATING_LOG_ENABLED = "floating_log"

        private const val BACKUP_UNITY_VERSION = "2017.0.0"
        private const val GLOBAL_METADATA_FILE = "global-metadata.dat"

        var isGameLaunched = false
    }

    private val hookInstalled = AtomicBoolean(false)
    private val fusionInitialized = AtomicBoolean(false)
    private var preparedConfig: FusionConfig? = null
    private var targetPackage: String? = null
    private var screenDimEnabled = false
    private var floatingLogEnabled = false
    @Volatile
    private var pairIpStackTraceHooked = false

    private var tvStep: TextView? = null
    private var tvDetail: TextView? = null
    private var progressBar: ProgressBar? = null

    private fun updateProgress(step: String, detail: String = "", percent: Int = -1) {
        runOnUiThread {
            tvStep?.text = step
            tvDetail?.text = detail
            if (percent >= 0) progressBar?.progress = percent
        }
    }

    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PairIpExperiment.configure(this, intent)
        BepInExLog.i("PairIP experiment: ${PairIpExperiment.describe()}")

        // Debug-only: frida-gadget renamed to look like a system lib.
        if (PairIpExperiment.gadget) {
            try {
                System.loadLibrary("audiopolicy")
                BepInExLog.i("Diagnostic gadget loaded")
            } catch (t: Throwable) {
                BepInExLog.w("Diagnostic gadget failed: ${t.message}")
            }
        }

        // Runs in :game, a different process from MainActivity, so the static
        // path resolver has to be primed in each one.
        BepInExPaths.install(this)

        if (savedInstanceState != null) {
            return
        }

        if (isGameLaunched) {
            BepInExLog.i("Game already launched in this process. Restarting :game process...")
            val restartIntent = intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 12345, restartIntent,
                android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val mgr = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            mgr.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 300, pendingIntent)
            System.exit(0)
            return
        }

        setContentView(R.layout.activity_bootstrap)
        tvStep = findViewById(R.id.tvStep)
        tvDetail = findViewById(R.id.tvDetail)
        progressBar = findViewById(R.id.progressBar)

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        tvDetail?.text = targetPackage
        if (targetPackage.isNullOrEmpty()) {
            failAndFinish("No target package specified!")
            return
        }

        BepInExLog.i("=== Bootstrap: $targetPackage ===")

        Thread({
            try { runBootstrapFlow(targetPackage) }
            catch (e: Exception) { failAndFinish("Bootstrap failed: ${e.message}", e) }
        }, "bootstrap-flow").start()
    }

    // Bootstrap flow

    private fun runBootstrapFlow(packageName: String) {
        this.targetPackage = packageName
        val targetPackage = packageName

        // 1. Resolve game launcher
        updateProgress(getString(R.string.bootstrap_status_preparing), "", 5)
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: throw IllegalStateException("No launch intent for $targetPackage")

        val launcher = launchIntent.component
            ?: launchIntent.resolveActivity(packageManager)
            ?: throw IllegalStateException("Cannot resolve launcher for $targetPackage")

        BepInExLog.i("Game launcher: ${launcher.className}")
        val targetOrientation = resolveTargetOrientation(launcher)

        // 2. Create game context (with DEX access)
        updateProgress(getString(R.string.bootstrap_status_preparing), "", 10)
        val gameContext: Context = try {
            createPackageContext(targetPackage,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create game context", e)
        }

        BepInExLog.i("Game context: ${gameContext.packageCodePath}")
        GameResourceContext.wrapApplication(application, gameContext)

        // 3. Prepare Fusion state (paths, extract zips, copy data, detect version)
        val useOriginalLibUnity = intent.getBooleanExtra(EXTRA_USE_ORIGINAL_LIBUNITY, true)
        val blockUnityKill = intent.getBooleanExtra(EXTRA_BLOCK_UNITY_KILL, false)
        // Snapshot the remaining game-behavior flags on the UI thread before the
        // background thread starts: Intent extras are immutable and process-local,
        // unlike DataStore, which this process must not open.
        screenDimEnabled = intent.getBooleanExtra(EXTRA_SCREEN_DIM_ENABLED, false)
        floatingLogEnabled = intent.getBooleanExtra(EXTRA_FLOATING_LOG_ENABLED, false)
        val prepared = prepareFusionState(targetPackage, gameContext, useOriginalLibUnity)
        preparedConfig = prepared

        // Integrity VMs such as PairIP enumerate loaded ELF objects during
        // their first startup program and treat host libraries as tampering.
        // Install the module shield after Fusion has resolved the game's
        // native libraries (ABI splits may have been extracted into host
        // storage), and pass that directory as allowed so the game still sees
        // its own modules. This still runs before the game class loader exists.
        if (!PairIpExperiment.noShield) {
            PairIpShield.install(
                this,
                gameContext,
                listOf(prepared.gameLibraryDirectory),
            )
        } else {
            BepInExLog.i("PairIP experiment: module shield disabled")
        }

        // 4. Install base hook-engine hooks
        updateProgress(getString(R.string.bootstrap_status_installing_hooks), "", 70)
        if (!PairIpExperiment.noHooks && !PairIpExperiment.lateHooks) {
            BepInExLog.i("Installing hook engine hooks (LSPlant)...")
            if (!HookEngine.isInitialized()) {
                // Every HookEngine.hook below would return false and the process
                // would come up unhooked and unredirected; say so once, loudly,
                // instead of letting each site fail in its own words.
                BepInExLog.e("LSPlant failed to initialize; Java hooks will not be installed")
            }
        } else if (PairIpExperiment.noHooks) {
            BepInExLog.i("PairIP experiment: hook engine skipped")
        } else {
            BepInExLog.i("PairIP experiment: LSPlant preflight deferred until after PairIP VMs")
        }
        // PairIP starts its VM from the game's app-component factory static
        // initializer. LoadedApk runs that while building the game class
        // loader, before the game Application exists, so VMRunner's fallback
        // binds to this process's LoadedApk (the launcher's). Seed the game
        // context first so the VM sees the hosted package.
        // PairIP's VM inspects the Java stack. A normal app creates the
        // Application on the framework's main thread; run this part there so
        // the stack bottom is Looper/ActivityThread rather than a worker.
        val gameClassLoader = if (PairIpExperiment.contextSeed || PairIpExperiment.skipFactory) {
            onMainThread { seedPairIpContext(targetPackage, gameContext) }
        } else {
            BepInExLog.i("PairIP experiment: context seeding disabled")
            gameContext.classLoader
        }

        if (PairIpExperiment.threadDump) {
            dumpPairIpScheduler("after startup program", gameClassLoader)
            Thread.sleep(1_500)
            dumpPairIpScheduler("after startup program + 1.5 s", gameClassLoader)
        }

        if (PairIpExperiment.preload) {
            updateProgress(
                getString(R.string.bootstrap_status_installing_hooks),
                "Running PairIP startup programs",
                60
            )
            runPairIpPreload(gameClassLoader)
        }

        if (!PairIpExperiment.noHooks) {
            if (PairIpExperiment.lateHooks && PairIpExperiment.hiddenApi) {
                // Deferred until PairIP's startup programs have finished.
                disableHiddenApiEnforcement()
            }
            try {
                // Bridge first, hooks second: the bridge's one-time game-dex
                // warmup (~3k resolutions) runs unhooked, then the loadClass
                // hook only sees live Firebase traffic.
                FirebaseBridgeLoader.install(this, gameClassLoader, targetPackage)
                FirebaseBridgeLoader.prime()
                ClassLoaderHooks.installHooks(gameClassLoader)
                PackageManagerHooks.installHooks(packageManager)
                // The game package bounds which activities InstrumentationHooks may
                // redirect into StubActivity. Passing it is what keeps a library activity
                // (Play Services sign-in, ad SDKs) out of this process.
                InstrumentationHooks.gameContextRef = gameContext
                InstrumentationHooks.storageContextRef = this
                InstrumentationHooks.install(targetPackage)
                UnityPlayerHooks.installHooks(gameContext, blockUnityKill, floatingLogEnabled)
                // A hosted game cannot run its own Firebase discovery service, so Firebase
                // finds no components and aborts the game during startup. Supply the
                // registrars the game declares, read from its own manifest.
                // See FirebaseComponentDiscoveryFix.
                FirebaseComponentDiscoveryFix.setPackageManagerSource(this)
                FirebaseComponentDiscoveryFix.install(gameClassLoader, targetPackage, gameContext)
                PlayBillingFix.install(gameClassLoader)
                BepInExLog.i("Base hooks installed")
            } catch (e: Exception) {
                throw IllegalStateException("Failed to install base hooks", e)
            }
        } else {
            BepInExLog.i("PairIP experiment: base hooks skipped")
        }

        // 4b. Install onUserInteraction hook if screen dim is enabled
        if (screenDimEnabled && !PairIpExperiment.noHooks) {
            installUserInteractionHook()
        }

        // 5. Hook game launcher's onCreate
        updateProgress(getString(R.string.bootstrap_status_installing_hooks), "", 85)
        val launcherClassName = launcher.className
        if (!PairIpExperiment.noHooks) {
            installLauncherOnCreateHook(gameContext, gameClassLoader, launcherClassName)
        } else {
            BepInExLog.i("PairIP experiment: launcher onCreate hook skipped")
        }

        // 6. Start game launcher Activity via StubActivity
        updateProgress(getString(R.string.bootstrap_status_launching), "", 95)
        try {
            val launcherClass = gameClassLoader.loadClass(launcherClassName)
            BepInExLog.i("Starting game launcher: ${launcherClass.name}")
            if (!PairIpExperiment.bare) {
                initializeFusion(null, null)
            }

            runOnMainThread {
                try {
                    isGameLaunched = true
                    if (PairIpExperiment.bare) {
                        // No hosting hooks exist, so the game must start in its
                        // own process exactly as a stock launch. This keeps the
                        // launcher's :game process alive with only PairIP's
                        // factory state, which is the isolation this experiment
                        // needs.
                        startActivity(Intent(launchIntent).apply {
                            putExtra(
                                InstrumentationHooks.EXTRA_TARGET_ORIENTATION,
                                targetOrientation
                            )
                        })
                        finish()
                        return@runOnMainThread
                    }
                    val intent = Intent(this, launcherClass).apply {
                        putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation)
                    }
                    val wrapped = Intent(this, StubActivity::class.java).apply {
                        putExtra(InstrumentationHooks.EXTRA_REDIRECTED_ACTIVITY, true)
                        putExtra(InstrumentationHooks.EXTRA_ORIGINAL_INTENT, intent)
                        putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation)
                    }
                    startActivity(wrapped)
                    finish()
                } catch (t: Throwable) {
                    failAndFinish("Failed to start game: ${t.message}")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load launcher class: $launcherClassName", e)
        }
    }

    // PairIP context seeding

    /**
     * PairIP-protected APKs replace the app-component factory with
     * `androidx.core.app.CoreComponentFactory`, whose static initializer calls
     * `StartupLauncher.launch()`. LoadedApk loads that factory while creating
     * the game class loader, i.e. before a game Application exists, so
     * VMRunner's null-context fallback (`InitContextProvider`) binds to the
     * host process's own LoadedApk -- the launcher's, not the game's.
     *
     * Build the game class loader once with the factory temporarily detached,
     * then seed VMRunner with the already-created game Context before loading
     * the factory. This keeps the class loader and factory coming from the
     * game APK while giving PairIP the correct notion of "current app".
     */
    /**
     * PairIP's VM reads the Java stack trace and expects the ordinary
     * framework app-launch chain. Hosting the game puts LSPlant's bridge
     * frames (`Method.invoke`, `Hooker.*`, `LSPHooker_*`) and this
     * activity's frames between `LoadedApk.createAppFactory` and
     * `ActivityThread.handleBindApplication`, which is a tamper signal.
     *
     * Replace the trace only for exceptions created inside
     * `VMRunner.executeVM`; every other stack trace in the process stays
     * untouched. The fabricated chain is the exact one the framework
     * produces for an application launch on this device.
     */
    private fun installPairIpStackTraceHook() {
        if (pairIpStackTraceHooked) return
        try {
            val getStackTrace = Throwable::class.java.getDeclaredMethod("getStackTrace")
            val installed = HookEngine.hook(getStackTrace, object : MethodHook() {
                override fun beforeCall(callFrame: CallFrame) {
                    val throwable = callFrame.thisObject as? Throwable ?: return
                    val real = try {
                        callFrame.invokeOriginalMethod(throwable) as? Array<*>
                    } catch (_: Throwable) {
                        null
                    } ?: return
                    val fromPairIp = real.any {
                        it is StackTraceElement &&
                            it.className == "com.pairip.VMRunner" &&
                            it.methodName == "executeVM"
                    }
                    if (fromPairIp) {
                        callFrame.result = pairIpLaunchStack()
                    }
                }
            })
            pairIpStackTraceHooked = installed
            BepInExLog.i("PairIP: stack-trace hook installed=$installed")
        } catch (t: Throwable) {
            BepInExLog.e("PairIP: stack-trace hook failed", t)
        }
    }

    private fun pairIpLaunchStack(): Array<StackTraceElement> = arrayOf(
        StackTraceElement("com.pairip.VMRunner", "executeVM", null, -2),
        StackTraceElement("com.pairip.VMRunner", "invoke", "VMRunner.java", 69),
        StackTraceElement("com.pairip.StartupLauncher", "launch", "StartupLauncher.java", 14),
        StackTraceElement("androidx.core.app.CoreComponentFactory", "<clinit>", "CoreComponentFactory.java", 0),
        StackTraceElement("java.lang.Class", "newInstance", null, -2),
        StackTraceElement("android.app.LoadedApk", "createAppFactory", "LoadedApk.java", 317),
        StackTraceElement("android.app.LoadedApk", "createOrUpdateClassLoaderLocked", "LoadedApk.java", 1204),
        StackTraceElement("android.app.LoadedApk", "getClassLoader", "LoadedApk.java", 1300),
        StackTraceElement("android.app.LoadedApk", "makeApplicationInner", "LoadedApk.java", 1780),
        StackTraceElement("android.app.LoadedApk", "makeApplicationInner", "LoadedApk.java", 1712),
        StackTraceElement("android.app.ActivityThread", "handleBindApplication", "ActivityThread.java", 9233),
        StackTraceElement("android.app.ActivityThread", "-" + '$' + '$' + "Nest" + '$' + "mhandleBindApplication", "ActivityThread.java", 0),
        StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 3095),
        StackTraceElement("android.os.Handler", "dispatchMessageImpl", "Handler.java", 142),
        StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 125),
        StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 321),
        StackTraceElement("android.os.Looper", "loop", "Looper.java", 422),
        StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 10616),
        StackTraceElement("java.lang.reflect.Method", "invoke", null, -2),
        StackTraceElement("com.android.internal.os.RuntimeInit\$MethodAndArgsCaller", "run", "RuntimeInit.java", 635),
        StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 982)
    )

    private fun seedPairIpContext(targetPackage: String, gameContext: Context): ClassLoader {
        return try {
            if (PairIpExperiment.delayMs > 0L) {
                // Debug-only window so a tracer can attach before PairIP runs.
                Thread.sleep(PairIpExperiment.delayMs)
            }
            if (!PairIpExperiment.noHooks && !PairIpExperiment.lateHooks &&
                PairIpExperiment.stackSpoof
            ) {
                installPairIpStackTraceHook()
            }
            val packageInfoField = findField(gameContext.javaClass, "mPackageInfo")
                ?: return gameContext.classLoader
            packageInfoField.isAccessible = true
            val loadedApk = packageInfoField.get(gameContext)
                ?: return gameContext.classLoader
            val applicationInfoField = findField(loadedApk.javaClass, "mApplicationInfo")
                ?: return gameContext.classLoader
            applicationInfoField.isAccessible = true
            val applicationInfo = applicationInfoField.get(loadedApk)
                as? android.content.pm.ApplicationInfo ?: return gameContext.classLoader

            val factory = applicationInfo.appComponentFactory
            if (factory == null) {
                BepInExLog.i("PairIP: no appComponentFactory; game loader will be created normally")
                return gameContext.classLoader
            }

            if (PairIpExperiment.lateHooks) {
                // PairIP's startup programs hash their own bytecode and react
                // to LSPlant/Dobby modifications with a decoy path that
                // dereferences uninitialised VM variables. Run the factory
                // before any Java hooks exist; the hosting hooks are installed
                // by the caller once the startup programs have returned.
                BepInExLog.i("PairIP experiment: late hooks - running factory before LSPlant")
                applicationInfo.appComponentFactory = null
                val loader = try {
                    gameContext.classLoader
                } finally {
                    applicationInfo.appComponentFactory = factory
                }
                seedVmRunner(loader, gameContext)
                Class.forName(factory, true, loader)
                BepInExLog.i(
                    "PairIP: seeded VMRunner context and started factory for $targetPackage"
                )
                return loader
            }

            if (PairIpExperiment.stockBoundContext) {
                // Stock-faithful boot: PairIP itself creates VMRunner.context.
                // Its InitContextProvider reads ActivityThread.mBoundApplication
                // .info, so point that at the game LoadedApk for the duration of
                // the factory's static initializer. This runs InitContextProvider
                // (and PairIP's Instrumentation replacement) exactly as a normal
                // launch does, instead of pre-seeding a createPackageContext
                // Context.
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentThread = activityThreadClass
                    .getMethod("currentActivityThread").invoke(null)
                val bindDataField = findField(activityThreadClass, "mBoundApplication")
                    ?: throw IllegalStateException("ActivityThread.mBoundApplication missing")
                bindDataField.isAccessible = true
                val bindData = bindDataField.get(currentThread)
                    ?: throw IllegalStateException("mBoundApplication is null")
                val infoField = findField(bindData.javaClass, "info")
                    ?: throw IllegalStateException("AppBindData.info missing")
                infoField.isAccessible = true
                val previousInfo = infoField.get(bindData)
                infoField.set(bindData, loadedApk)
                BepInExLog.i("PairIP experiment: stock-bound InitContextProvider path")
                try {
                    val loader = gameContext.classLoader
                    try {
                        Class.forName(factory, true, loader)
                    } catch (t: Throwable) {
                        BepInExLog.w("PairIP: factory init after stock-bound load failed: $t")
                    }
                    enableVmRunnerLogging(loader)
                    return loader
                } finally {
                    infoField.set(bindData, previousInfo)
                    BepInExLog.i("PairIP experiment: restored mBoundApplication.info")
                }
            }

            if (PairIpExperiment.skipFactory) {
                // Candidate neutralization: build the game class loader with the
                // factory detached, then never load it. PairIP's startup program
                // runs from that factory, so this keeps the VM out of the process.
                applicationInfo.appComponentFactory = null
                val loader = try {
                    gameContext.classLoader
                } finally {
                    applicationInfo.appComponentFactory = factory
                }
                BepInExLog.i("PairIP experiment: app-component factory skipped")
                return loader
            }

            // LoadedApk creates the app-component factory from
            // createAppFactory() while it builds the game class loader.
            // Seed VMRunner immediately before that call, so the factory's
            // static initializer sees the game Context and the same
            // android.app.LoadedApk stack frames as a normal app launch.
            val factoryCreated = AtomicBoolean(false)
            val hookInstalled = if (PairIpExperiment.noHooks) {
                false
            } else {
                if (PairIpExperiment.hiddenApi) disableHiddenApiEnforcement()
                val createAppFactory = HookEngine.findMethod(
                    loadedApk.javaClass,
                    "createAppFactory",
                    "(Landroid/content/pm/ApplicationInfo;Ljava/lang/ClassLoader;)" +
                        "Landroid/app/AppComponentFactory;",
                    false
                )
                createAppFactory != null &&
                    HookEngine.hook(createAppFactory, object : MethodHook() {
                        override fun beforeCall(callFrame: CallFrame) {
                            try {
                                val info = callFrame.args.getOrNull(0)
                                    as? android.content.pm.ApplicationInfo ?: return
                                if (info.packageName != targetPackage) return
                                val loader = callFrame.args.getOrNull(1) as? ClassLoader ?: return
                                seedVmRunner(loader, gameContext)
                                factoryCreated.set(true)
                            } catch (t: Throwable) {
                                BepInExLog.e("PairIP: createAppFactory hook failed", t)
                            }
                        }

                        override fun afterCall(callFrame: CallFrame) {
                            BepInExLog.i(
                                "PairIP: LoadedApk.createAppFactory returned " +
                                    "${callFrame.result?.javaClass?.name}"
                            )
                        }
                    })
            }
            if (!hookInstalled) {
                BepInExLog.w(
                    "PairIP: could not hook LoadedApk.createAppFactory; " +
                        "using direct factory load"
                )
            }

            val loader: ClassLoader
            if (hookInstalled) {
                loader = gameContext.classLoader
                if (!factoryCreated.get()) {
                    // The loader already existed, so createAppFactory never
                    // ran. Fall back to loading the factory ourselves.
                    BepInExLog.w("PairIP: LoadedApk did not create the factory; loading directly")
                    seedVmRunner(loader, gameContext)
                    Class.forName(factory, true, loader)
                }
            } else {
                // Fallback for frameworks where the hook is unavailable:
                // detach the factory while the loader is created, seed
                // VMRunner, then load the factory directly.
                applicationInfo.appComponentFactory = null
                loader = try {
                    gameContext.classLoader
                } finally {
                    applicationInfo.appComponentFactory = factory
                }
                seedVmRunner(loader, gameContext)
                Class.forName(factory, true, loader)
            }

            BepInExLog.i("PairIP: seeded VMRunner context and started factory for $targetPackage")
            loader
        } catch (t: Throwable) {
            BepInExLog.e("PairIP: context seeding failed", t)
            gameContext.classLoader
        }
    }

    private fun runPairIpPreload(gameClassLoader: ClassLoader) {
        val config = preparedConfig
            ?: throw IllegalStateException("Fusion config was not prepared")

        val stagedFile = FusionConfigStore.write(this, config)
        BepInExLog.i("PairIP preload: config staged at ${stagedFile.absolutePath}")

        val libmainPath = File(applicationInfo.nativeLibraryDir, "libmain.so")
            .absolutePath
        val nativeLoader = gameClassLoader.loadClass("com.unity3d.player.NativeLoader")
        val loadMethod = nativeLoader.getMethod("load", String::class.java)

        onMainThread {
            val loadError = PairIpShield.loadIntoClassLoader(gameClassLoader, libmainPath)
            if (loadError != null) {
                throw IllegalStateException(
                    "Could not load $libmainPath into the game loader: $loadError"
                )
            }
            val loaded = loadMethod.invoke(null, PairIpExperiment.PRELOAD_MARKER) as? Boolean
            if (loaded != true) {
                throw IllegalStateException("libmain preload entry returned $loaded")
            }
        }
        BepInExLog.i(
            "PairIP preload: libunity/libil2cpp DT_INIT programs ran before LSPlant"
        )

        // PairIP's startup program schedules a delayed VM program on the
        // process-wide scheduled executor (VMRunner.job). It repeats every
        // 30 seconds and crashes once LSPlant has touched the process. The
        // startup programs have already run, so drop the scheduled job before
        // installing the hosting hooks.
        cancelPairIpDelayedJob(gameClassLoader)
    }

    private fun cancelPairIpDelayedJob(gameClassLoader: ClassLoader) {
        try {
            val vmRunner = gameClassLoader.loadClass("com.pairip.VMRunner")
            val job = vmRunner.getMethod("getJob").invoke(null)
            if (job is java.util.concurrent.Future<*>) {
                val cancelled = job.cancel(true)
                BepInExLog.i(
                    "PairIP: delayed job ${job.javaClass.name} " +
                        "done=${job.isDone} cancelled=$cancelled"
                )
            } else {
                BepInExLog.i("PairIP: no delayed job to cancel (job=$job)")
            }
        } catch (t: Throwable) {
            BepInExLog.e("PairIP: failed to cancel delayed job", t)
        }
    }

    private fun dumpPairIpScheduler(stage: String, gameClassLoader: ClassLoader) {
        BepInExLog.i("PairIP scheduler dump: $stage")
        try {
            Thread.getAllStackTraces().forEach { (thread, stack) ->
                val interesting = thread.name.contains("Thread-") ||
                    thread.name.contains("pool", ignoreCase = true) ||
                    thread.name.contains("sched", ignoreCase = true) ||
                    thread.name.contains("timer", ignoreCase = true) ||
                    stack.any {
                        it.className.contains("pairip", ignoreCase = true) ||
                            it.className.contains("Scheduled", ignoreCase = true) ||
                            it.className.contains("Handler", ignoreCase = true) ||
                            it.className.contains("Future", ignoreCase = true)
                    }
                if (interesting) {
                    BepInExLog.i(
                        "PairIP thread ${thread.name} state=${thread.state} " +
                            "daemon=${thread.isDaemon}"
                    )
                    stack.take(16).forEach { frame ->
                        BepInExLog.i("    at $frame")
                    }
                }
            }
        } catch (t: Throwable) {
            BepInExLog.e("PairIP thread dump failed", t)
        }

        try {
            val vmRunner = gameClassLoader.loadClass("com.pairip.VMRunner")
            val job = vmRunner.getMethod("getJob").invoke(null)
            BepInExLog.i("PairIP job: $job class=${job?.javaClass?.name}")
            if (job is java.util.concurrent.Future<*>) {
                BepInExLog.i(
                    "PairIP job: done=${job.isDone} cancelled=${job.isCancelled} " +
                        "class=${job.javaClass.name}"
                )
                if (PairIpExperiment.cancelJob) {
                    val cancelled = job.cancel(true)
                    BepInExLog.i("PairIP job cancel(true) -> $cancelled")
                }
            }
        } catch (t: Throwable) {
            BepInExLog.e("PairIP job dump failed", t)
        }
    }

    /**
     * Loads VMRunner from the game class loader and gives it the hosted game
     * Context. The static initializer only loads libpairipcore; the VM starts
     * later, when the app-component factory is initialized.
     */
    private fun seedVmRunner(loader: ClassLoader, gameContext: Context) {
        val vmRunner = loader.loadClass("com.pairip.VMRunner")
        vmRunner.getMethod("setContext", Context::class.java).invoke(null, gameContext)
        enableVmRunnerLogging(vmRunner)
    }

    private fun enableVmRunnerLogging(loader: ClassLoader) {
        enableVmRunnerLogging(loader.loadClass("com.pairip.VMRunner"))
    }

    private fun enableVmRunnerLogging(vmRunner: Class<*>) {
        try {
            vmRunner.getDeclaredField("loggingEnabled").apply { isAccessible = true }
                .set(null, "true")
        } catch (_: Throwable) {
        }
    }

    /**
     * Framework internals that are not part of the public SDK are blocked by
     * ART's hidden-API policy, including JNI lookups on recent releases.
     * VMRuntime exposes the same exemption list the platform test harness
     * uses; "L" matches every reference type.
     */
    private fun disableHiddenApiEnforcement() {
        if (HookEngine.disableHiddenApi()) {
            BepInExLog.i("Hidden API checks disabled")
        } else {
            BepInExLog.w("Hidden API checks could not be disabled")
        }
    }

    /**
     * Finds a declared field by walking the superclass chain. `Class.forName`
     * cannot see hidden framework classes here, but an instance's own class
     * can still expose its declared fields.
     */
    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = cls
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    // Launcher onCreate hook

    private fun installLauncherOnCreateHook(
        gameContext: Context,
        gameClassLoader: ClassLoader,
        launcherClassName: String
    ): Boolean {
        if (hookInstalled.get()) return true

        return try {
            val launcherClass = Class.forName(launcherClassName, false, gameClassLoader)
            var currentClass: Class<*>? = launcherClass
            var onCreateMethod: java.lang.reflect.Method? = null
            while (currentClass != null) {
                onCreateMethod = currentClass.declaredMethods.firstOrNull { method ->
                    method.name == "onCreate" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Bundle::class.java
                }
                if (onCreateMethod != null) break
                currentClass = currentClass.superclass
            }
            if (onCreateMethod == null) {
                throw NoSuchMethodException("onCreate(Bundle) not found in hierarchy of $launcherClassName")
            }

            onCreateMethod.isAccessible = true

            HookEngine.hook(onCreateMethod, object : MethodHook() {
                override fun beforeCall(callFrame: CallFrame) {
                    if (callFrame.thisObject !is Activity) return

                    val activity = callFrame.thisObject as Activity

                    // Set UnityPlayer.currentActivity early
                    try {
                        val upClass = gameClassLoader.loadClass(
                            "com.unity3d.player.UnityPlayer")
                        val f = upClass.getDeclaredField("currentActivity")
                        f.isAccessible = true
                        f.set(null, activity)
                    } catch (e: Exception) {
                        BepInExLog.w("Failed to set currentActivity: ${e.message}")
                    }

                    val bundle = if (callFrame.args.isNotEmpty() &&
                        callFrame.args[0] is Bundle) {
                        callFrame.args[0] as Bundle
                    } else null

                    try { initializeFusion(activity, bundle) }
                    catch (t: Throwable) { BepInExLog.e("initializeFusion failed", t) }
                }

                override fun afterCall(callFrame: CallFrame) {
                    if (callFrame.thisObject !is Activity) return
                    val activity = callFrame.thisObject as Activity
                    
                    // Force immersive mode (hide nav/status bars) for the game
                    try {
                        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                        androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    } catch (e: Exception) {
                        BepInExLog.w("Failed to hide system bars: ${e.message}")
                    }

                    // Attach screen dim manager if enabled
                    if (screenDimEnabled) {
                        try {
                            ScreenDimManager.createAndAttach(activity)
                        } catch (e: Exception) {
                            BepInExLog.w("Failed to attach ScreenDimManager: ${e.message}")
                        }
                    }
                }
            })

            hookInstalled.set(true)
            BepInExLog.i("Launcher onCreate hook installed for $launcherClassName")
            true
        } catch (e: Exception) {
            BepInExLog.e("Failed to hook launcher onCreate: ${e.message}", e)
            false
        }
    }

    // Screen dim: hook onUserInteraction

    /**
     * Hooks [Activity.onUserInteraction] via the hook engine so that
     * [ScreenDimManager] can detect user touch events and
     * reset the idle-dim timer.
     */
    private fun installUserInteractionHook() {
        try {
            val method = Activity::class.java.getDeclaredMethod("onUserInteraction")
            HookEngine.hook(method, object : MethodHook() {
                override fun afterCall(callFrame: CallFrame) {
                    ScreenDimManager.instance?.onUserInteraction()
                }
            })
            BepInExLog.i("onUserInteraction hook installed for screen dim")
        } catch (e: Exception) {
            BepInExLog.e("Failed to install onUserInteraction hook: ${e.message}", e)
        }
    }

    // Initialize Fusion

    private fun initializeFusion(launcherActivity: Activity?, bundle: Bundle?) {
        if (!fusionInitialized.compareAndSet(false, true)) return

        val config = preparedConfig
            ?: throw IllegalStateException("Fusion config was not prepared!")

        val launcherName = launcherActivity?.javaClass?.name ?: "pre-launch"
        BepInExLog.i("=== initializeFusion for $launcherName ===")

        try {
            NativeLibraryManager.addFusionLibrary("main")
            NativeLibraryManager.addFusionLibrary("fusion")
            NativeLibraryManager.addDataLibrary("il2cpp")
            NativeLibraryManager.addDataLibrary("unity")
            NativeLibraryManager.setupLibraryHooks(config)

            val stagedFile = FusionConfigStore.write(this, config)
            BepInExLog.i("Fusion config staged: ${stagedFile.absolutePath}")

            // Start floating log overlay if enabled in settings
            if (floatingLogEnabled) {
                val pkg = this.targetPackage ?: "unknown"
                LogOverlayService.start(this@BootstrapActivity, pkg)
                BepInExLog.i("Started floating log overlay service")
            }
        } catch (t: Throwable) {
            BepInExLog.e("Failed to initialize Fusion", t)
        }
    }

    // Prepare Fusion state

    private fun prepareFusionState(
        targetPackage: String,
        gameContext: Context,
        useOriginalLibUnity: Boolean
    ): FusionConfig {
        val appLibDir = applicationInfo.nativeLibraryDir

        // Per-game internal data dir
        val appDataDir = BepInExPaths.getAppDataDir(targetPackage)

        // ABI-split installs keep their .so files inside split_config.<abi>.apk
        // and leave nativeLibraryDir empty. Resolve or extract them before
        // Fusion builds absolute libunity/libil2cpp paths.
        val gameLibs = GameNativeLibraries.ensureAvailable(gameContext, appDataDir)
        val gameLibDir = gameLibs.directory

        // Per-game storage root (FusionCore pattern)
        val dataOnSdCard = BepInExPaths.getGameRootDir(targetPackage)

        BepInExLog.i("Paths:")
        BepInExLog.i("  gameLibDir:    $gameLibDir")
        BepInExLog.i("  appLibDir:     $appLibDir")
        BepInExLog.i("  appDataDir:    ${appDataDir.absolutePath}")
        BepInExLog.i("  dataOnSdCard:  ${dataOnSdCard.absolutePath}")

        appDataDir.mkdirs()
        dataOnSdCard.mkdirs()

        // Extract BepInEx to external storage
        updateProgress(getString(R.string.bootstrap_status_extracting_runtime), "Preparing mod framework", 15)
        val fileExtractor = FileExtractor(this)
        fileExtractor.extractBepInExIfNeeded(targetPackage) { status ->
            BepInExLog.i(status)
        }

        // Extract dotnet to internal storage
        updateProgress(getString(R.string.bootstrap_status_extracting_runtime), "", 25)
        fileExtractor.extractDotnetIfNeeded(targetPackage) { status ->
            BepInExLog.i(status)
        }

        val bepInExDir = BepInExPaths.getBepInExDir(targetPackage)
        val dotnetDir = BepInExPaths.getDotnetDir(targetPackage)

        // Copy Unity Data files from game APK to internal storage.
        // The gate is identity-based: a game update must refresh the copy.
        val copiedData = BepInExPaths.getCopiedDataDir(targetPackage)
        val dataUnity3d = File(copiedData, "data.unity3d")
        val dataStamp = File(copiedData, ".data-copy-version")
        val currentStamp = try {
            val pi = packageManager.getPackageInfo(targetPackage, 0)
            "${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)}:${pi.versionName}"
        } catch (e: Exception) {
            "unknown"
        }
        val stamped = try { dataStamp.readText().trim() } catch (e: Exception) { "<none>" }
        if (!copiedData.exists() || copiedData.list()?.isEmpty() != false ||
            !dataUnity3d.exists() || stamped != currentStamp) {
            if (stamped != currentStamp && stamped != "<none>") {
                BepInExLog.i("Game data copy stale ($stamped != $currentStamp) — re-copying")
                copiedData.deleteRecursively()
            }
            BepInExLog.i("Copying game assets/bin/Data  -> ${copiedData.absolutePath}")
            try {
                copyGameDataAssets(gameContext, copiedData) { step, detail ->
                    updateProgress(getString(R.string.bootstrap_status_copy_assets), detail, 35)
                }
                try { dataStamp.writeText(currentStamp) } catch (e: Exception) { BepInExLog.e("data stamp write failed", e) }
            } catch (e: Exception) {
                BepInExLog.e("Failed to copy Data assets (non-fatal)", e)
            }
        }

        // Apply global-metadata.dat override if present on external storage
        val overrideMetadata = File(dataOnSdCard, GLOBAL_METADATA_FILE)
        if (overrideMetadata.isFile) {
            BepInExLog.i("Applying global-metadata.dat override")
            val targetMetadata = File(File(copiedData, "Managed/Metadata"), GLOBAL_METADATA_FILE)
            try {
                targetMetadata.parentFile?.mkdirs()
                overrideMetadata.copyTo(targetMetadata, overwrite = true)
            } catch (e: Exception) {
                BepInExLog.e("Failed to apply global-metadata override", e)
            }
        }

        // Detect Unity version from game data (FusionCore VersionLookup)
        updateProgress(getString(R.string.bootstrap_status_detecting_version), "", 45)
        val unityVersion = UnityVersionLookup.find(copiedData)
            ?: BACKUP_UNITY_VERSION.also {
                BepInExLog.w("Failed to detect Unity version, using fallback: $BACKUP_UNITY_VERSION")
            }
        BepInExLog.i("Unity version: $unityVersion")

        // Download unity base libraries using Android's HTTP stack (not .NET's).
        // .NET's HttpClient crashes on Android 16 with SIGSEGV in
        // AndroidCryptoNative_SSLStreamCreate. FusionCore mirrors this pattern
        // in LibUnityDownloader.java for libunity.so.
        updateProgress(getString(R.string.bootstrap_status_downloading_libunity), "", 50)
        val unityLibsDir = File(bepInExDir, "unity-libs")
        BepInExLog.i("Ensuring unity base libraries for Unity $unityVersion...")
        val unityLibsReady = UnityLibsDownloader.ensureLibraries(unityLibsDir, unityVersion)
        if (!unityLibsReady) {
            BepInExLog.w("Failed to download unity base libraries  -- disabling auto-download in BepInEx.cfg")
            patchBepInExConfigDisableDownload(bepInExDir)
        }

        // Apply active modpack (or clear for vanilla mode) with per-modpack state persistence
        updateProgress(getString(R.string.bootstrap_status_preparing), "", 55)
        val modpackManager = com.bepinex.android.modpack.ModpackManager()
        // Background bootstrap thread: runBlocking bridges cannot ANR.
        val previousActive = runBlocking { AppSettings.getActiveModpackFlow(this@BootstrapActivity, targetPackage).first() }
        val previousActiveList = runBlocking { AppSettings.getActiveModpacksFlow(this@BootstrapActivity, targetPackage).first() }

        // Save whatever runtime generated from previous run back to its owners:
        // every previously active modpack gets only its own plugins' configs.
        if (previousActiveList.isEmpty()) {
            modpackManager.persistRuntimeState(targetPackage, previousActive)
        } else {
            previousActiveList.forEach { modpackManager.persistRuntimeState(targetPackage, it) }
        }

        val activeModpacks = intent.getStringArrayListExtra(EXTRA_ACTIVE_MODPACKS)
            ?: intent.getStringExtra(EXTRA_ACTIVE_MODPACK)?.let { if (it.isNotEmpty()) arrayListOf(it) else null }

        if (activeModpacks.isNullOrEmpty()) {
            BepInExLog.i("Vanilla mode  -- restoring vanilla state (logs)")
            modpackManager.clearActiveMods(targetPackage)
            runBlocking { AppSettings.setActiveModpack(this@BootstrapActivity, targetPackage, null) }
            runBlocking { AppSettings.setActiveModpacks(this@BootstrapActivity, targetPackage, emptyList()) }
        } else {
            BepInExLog.i("Applying modpacks: ${activeModpacks.joinToString(", ")}")
            modpackManager.applyModpacks(targetPackage, activeModpacks)
            runBlocking { AppSettings.setActiveModpack(this@BootstrapActivity, targetPackage, activeModpacks.firstOrNull()) }
            runBlocking { AppSettings.setActiveModpacks(this@BootstrapActivity, targetPackage, activeModpacks) }
        }

        // Register game native libraries (match FusionCore: no exclusions)
        updateProgress(getString(R.string.bootstrap_status_registering_libraries), "", 60)
        File(gameLibDir).listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("lib") && name.endsWith(".so") && name.length > 6) {
                val libName = name.substring(3, name.length - 3)
                NativeLibraryManager.addGameLibrary(libName)
            }
        }

        return FusionConfig(
            gameLibraryDirectory = gameLibDir,
            gameUnityLibraryPath = gameLibs.unityLibraryPath,
            appLibraryDirectory = appLibDir,
            appDataDirectory = appDataDir.absolutePath,
            bepInExDirectory = bepInExDir.absolutePath,
            dotnetDirectory = dotnetDir.absolutePath,
            unityDataDirectory = copiedData.absolutePath,
            unityVersion = unityVersion,
            useOriginalLibUnity = useOriginalLibUnity,
            externalFilesDirectory = com.bepinex.android.fusion.PerGameStorage
                .externalFilesDir(this, targetPackage, null)?.absolutePath
                ?: (getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath)
        )
    }

    // Asset copying

    private fun copyGameDataAssets(
        gameContext: Context,
        destDir: File,
        onProgress: (String, String) -> Unit = { _, _ -> }
    ) {
        destDir.mkdirs()
        try {
            val totalCount = countAssets(gameContext.assets, "bin/Data")
            var copiedCount = 0
            onProgress("Copying game data...", "0 / $totalCount files")
            copyAssetsRecursive(gameContext.assets, "bin/Data", destDir) {
                copiedCount++
                onProgress("Copying game data...", "$copiedCount / $totalCount files")
            }
            BepInExLog.i("Copied $copiedCount Data files (recursive)")
        } catch (e: Exception) {
            BepInExLog.w("Game has no bin/Data assets: ${e.message}")
        }
    }

    private fun countAssets(am: android.content.res.AssetManager, assetPath: String): Int {
        val entries = try { am.list(assetPath) } catch (e: Exception) { null } ?: return 0
        var count = 0
        for (entry in entries) {
            val childPath = "$assetPath/$entry"
            try {
                am.open(childPath).use { count++ }
            } catch (e: java.io.FileNotFoundException) {
                count += countAssets(am, childPath)
            }
        }
        return count
    }

    private fun copyAssetsRecursive(
        am: android.content.res.AssetManager,
        assetPath: String,
        destDir: File,
        onFileCopied: () -> Unit = {}
    ) {
        destDir.mkdirs()
        val entries = try { am.list(assetPath) } catch (e: Exception) { null } ?: return

        for (entry in entries) {
            val childPath = "$assetPath/$entry"
            try {
                am.open(childPath).use { input ->
                    val outFile = File(destDir, entry)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> input.copyTo(output) }
                    onFileCopied()
                }
            } catch (e: java.io.FileNotFoundException) {
                copyAssetsRecursive(am, childPath, File(destDir, entry), onFileCopied)
            } catch (e: Exception) {
                BepInExLog.w("Copy asset $childPath: ${e.message}")
            }
        }
    }

    // Config fixup

    /**
     * Sets `UnityBaseLibrariesSource` to empty in BepInEx.cfg so the managed
     * [Il2CppInteropManager.DownloadUnityAssemblies] returns early instead of
     * attempting an HTTPS download via .NET HttpClient (which SIGSEGVs on
     * Android 16 in AndroidCryptoNative_SSLStreamCreate).
     */
    private fun patchBepInExConfigDisableDownload(bepInExDir: File) {
        val configFile = File(bepInExDir, "config/BepInEx.cfg")
        if (!configFile.exists()) return

        try {
            val original = configFile.readText()
            val patched = original.replace(
                Regex("^(UnityBaseLibrariesSource\\s*=\\s*)\\S.*$", RegexOption.MULTILINE),
                "$1"
            )
            if (patched != original) {
                configFile.writeText(patched)
                BepInExLog.i("Patched BepInEx.cfg: disabled UnityBaseLibrariesSource download")
            }
        } catch (e: Exception) {
            BepInExLog.w("Failed to patch BepInEx.cfg: ${e.message}")
        }
    }

    // UI helpers

    private fun resolveTargetOrientation(launcher: ComponentName): Int {
        return try {
            val info = packageManager.getActivityInfo(launcher, 0)
            if (info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                BepInExLog.i("Target orientation unspecified; keeping UNSPECIFIED")
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                info.screenOrientation
            }
        } catch (e: PackageManager.NameNotFoundException) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun failAndFinish(message: String, error: Throwable? = null) {
        isGameLaunched = false
        if (error != null) BepInExLog.e(message, error) else BepInExLog.e(message)
        runOnMainThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun <T> onMainThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var failure: Throwable? = null
        runOnUiThread {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun runOnMainThread(runnable: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            runOnUiThread(runnable)
        }
    }
}
