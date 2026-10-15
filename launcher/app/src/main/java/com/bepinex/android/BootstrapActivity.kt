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
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bootstrap activity that injects BepInEx into the target game process
 * via Pine hooks, custom libmain.so/libfusion.so, and CoreCLR bootstrap.
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
        preparedConfig = prepareFusionState(targetPackage, gameContext, useOriginalLibUnity)

        // 4. Install base Pine hooks
        updateProgress(getString(R.string.bootstrap_status_installing_hooks), "", 70)
        BepInExLog.i("Installing Pine hooks...")
        try {
            // Bridge first, hooks second: the bridge's one-time game-dex
            // warmup (~3k resolutions) runs unhooked, then the loadClass
            // hook only sees live Firebase traffic.
            FirebaseBridgeLoader.install(this, gameContext.classLoader, targetPackage)
            FirebaseBridgeLoader.prime()
            ClassLoaderHooks.installHooks(gameContext.classLoader)
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
            FirebaseComponentDiscoveryFix.install(gameContext.classLoader, targetPackage, gameContext)
            PlayBillingFix.install(gameContext.classLoader)
            BepInExLog.i("Base hooks installed")
        } catch (e: Exception) {
            throw IllegalStateException("Failed to install base hooks", e)
        }

        // 4b. Install onUserInteraction hook if screen dim is enabled
        if (screenDimEnabled) {
            installUserInteractionHook()
        }

        // 5. Hook game launcher's onCreate
        updateProgress(getString(R.string.bootstrap_status_installing_hooks), "", 85)
        val launcherClassName = launcher.className
        installLauncherOnCreateHook(gameContext, gameContext.classLoader, launcherClassName)

        // 6. Start game launcher Activity via StubActivity
        updateProgress(getString(R.string.bootstrap_status_launching), "", 95)
        try {
            val launcherClass = gameContext.classLoader.loadClass(launcherClassName)
            BepInExLog.i("Starting game launcher: ${launcherClass.name}")
            initializeFusion(null, null)

            runOnMainThread {
                try {
                    isGameLaunched = true
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

            Pine.hook(onCreateMethod, object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
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

                override fun afterCall(callFrame: Pine.CallFrame) {
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
     * Hooks [Activity.onUserInteraction] via Pine so that
     * [ScreenDimManager] can detect user touch events and
     * reset the idle-dim timer.
     */
    private fun installUserInteractionHook() {
        try {
            val method = Activity::class.java.getDeclaredMethod("onUserInteraction")
            Pine.hook(method, object : MethodHook() {
                override fun afterCall(callFrame: Pine.CallFrame) {
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
        val gameLibDir = gameContext.applicationInfo.nativeLibraryDir
        val appLibDir = applicationInfo.nativeLibraryDir

        // Per-game internal data dir
        val appDataDir = BepInExPaths.getAppDataDir(targetPackage)

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

        // Save whatever runtime generated from previous run back to its owner
        modpackManager.persistRuntimeState(targetPackage, previousActive)

        val activeModpacks = intent.getStringArrayListExtra(EXTRA_ACTIVE_MODPACKS)
            ?: intent.getStringExtra(EXTRA_ACTIVE_MODPACK)?.let { if (it.isNotEmpty()) arrayListOf(it) else null }

        if (activeModpacks.isNullOrEmpty()) {
            BepInExLog.i("Vanilla mode  -- restoring vanilla cfg/logs")
            modpackManager.clearActiveMods(targetPackage)
            runBlocking { AppSettings.setActiveModpack(this@BootstrapActivity, targetPackage, null) }
        } else {
            BepInExLog.i("Applying modpacks: ${activeModpacks.joinToString(", ")}")
            modpackManager.applyModpacks(targetPackage, activeModpacks)
            runBlocking { AppSettings.setActiveModpack(this@BootstrapActivity, targetPackage, activeModpacks.firstOrNull()) }
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
            appLibraryDirectory = appLibDir,
            appDataDirectory = appDataDir.absolutePath,
            bepInExDirectory = bepInExDir.absolutePath,
            dotnetDirectory = dotnetDir.absolutePath,
            unityDataDirectory = copiedData.absolutePath,
            unityVersion = unityVersion,
            useOriginalLibUnity = useOriginalLibUnity,
            externalFilesDirectory = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
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

    private fun runOnMainThread(runnable: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            runOnUiThread(runnable)
        }
    }
}
