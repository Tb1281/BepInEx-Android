package com.bepinex.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.bepinex.android.log.BepInExLogReader
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.navigation.BepInExNavHost
import com.bepinex.android.ui.theme.BepInExTheme
import java.io.File

import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bepinex.android.ui.components.CrashRecoveryDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/**
 * Main launcher Activity -- Compose UI for multi-game BepInEx mod management.
 *
 * On launch, auto-detects installed Unity IL2CPP games via [GameDetector].
 * User selects a game, manages mods, and launches it through [BootstrapActivity].
 *
 * Extends AppCompatActivity (not ComponentActivity) so that per-app locales via
 * AppCompatDelegate.setApplicationLocales() are applied by the framework,
 * including Activity recreation on language change.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var leftLauncher = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The result carries no game context; the toggle parked the package
        // in the ViewModel. Consume it (read-and-clear) whichever way the
        // user decided, so it cannot leak into a later enable.
        val pkg = viewModel.takePendingFloatingLogPackage()
        if (Settings.canDrawOverlays(this)) {
            if (pkg != null) enableFloatingLog(pkg)
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // POST_NOTIFICATIONS (API 33+) for the overlay service's foreground-service
    // notification. The service runs without it, but its notification is then
    // silent — request once when the user enables the floating log, never gate.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Same consumption as the overlay result: take the parked package
        // exactly once. The result value itself is ignored by design (the
        // permission never gates, see ensureNotificationPermission), but the
        // enable it belongs to still continues — for the parked game only.
        // A null package means the parked enable is gone (e.g. the process
        // was recreated); abandon it rather than guessing a game.
        val pkg = viewModel.takePendingFloatingLogPackage()
        if (pkg != null) enableFloatingLog(pkg)
    }

    // Enables the floating log for exactly [packageName]. Every caller
    // either is the toggle itself (which knows its game) or a permission
    // result that consumed the parked package — the game is never
    // re-derived here (no selected-game fallback), so an enable can never
    // be recorded for the wrong game.
    private fun enableFloatingLog(packageName: String) {
        viewModel.setFloatingLogInGame(packageName, true)
        ensureNotificationPermission(packageName)
        com.bepinex.android.log.LogOverlayService.start(this, packageName)
    }

    private fun ensureNotificationPermission(packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // The result comes back without any game context; park the
            // package in the ViewModel so the result is attributed to the
            // game whose enable asked for it.
            viewModel.parkPendingFloatingLogPackage(packageName)
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        BepInExPaths.install(this)
        AppSettings.initialize(this)

        BepInExLog.init(this)
        BepInExLog.i("=== BepInEx Launcher ===")
        BepInExLog.i("Device: ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.SDK_INT}")

        checkStoragePermission()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            BepInExTheme(themeMode = uiState.themeMode, dynamicColor = uiState.dynamicColor) {
                BepInExNavHost(
                    detectedGames = uiState.detectedGames,
                    selectedGame = uiState.selectedGame,
                    isScanning = uiState.isScanning,
                    isFrameworkReady = uiState.isFrameworkReady,
                    isExtracting = uiState.isExtracting,
                    extractionStatus = uiState.extractionStatus,
                    themeMode = uiState.themeMode,
                    language = uiState.language,
                    dynamicColor = uiState.dynamicColor,
                    animationDisabled = uiState.animationDisabled,
                    onSelectGame = { viewModel.selectGame(it) },
                    onRescan = { viewModel.rescan() },
                    onLaunch = { modpackNames -> launchGame(uiState.selectedGame, modpackNames, uiState.isFrameworkReady, uiState.isExtracting) },
                    onThemeChanged = { viewModel.setTheme(it) },
                    onLanguageChanged = { lang ->
                        // Persist + apply; AppCompatDelegate recreates the Activity
                        // to apply the new locale. No manual recreate() needed.
                        viewModel.setLanguage(lang)
                    },
                    onDynamicColorChanged = { viewModel.setDynamicColor(it) },
                    onAnimationDisabledChanged = { viewModel.setAnimationDisabled(it) },
                    onFloatingLogInGameChanged = { gamePackage, enabled ->
                        if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                overlayPermissionLauncher.launch(intent)
                                // The overlay result comes back without
                                // any game context; park whose toggle this
                                // is (in the ViewModel, so it survives
                                // recreation) for the result to consume.
                                // Parked only after launch() succeeded, so
                                // the failure path below leaves nothing parked.
                                viewModel.parkPendingFloatingLogPackage(gamePackage)
                            } catch (e: Exception) {
                                BepInExLog.e("Failed to open overlay permission settings", e)
                                Toast.makeText(this@MainActivity, "Please grant 'Draw over other apps' in app settings", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            if (enabled) {
                                enableFloatingLog(gamePackage)
                            } else {
                                viewModel.setFloatingLogInGame(gamePackage, false)
                                com.bepinex.android.log.LogOverlayService.stop(this@MainActivity)
                            }
                        }
                    },
                    onScreenDimInGameChanged = { gamePackage, enabled ->
                        viewModel.setScreenDimInGame(gamePackage, enabled)
                    },
                    onClearBepInEx = { 
                        viewModel.clearBepInEx(it)
                        Toast.makeText(this, getString(R.string.done), Toast.LENGTH_SHORT).show()
                    },
                    onClearDotnet = {
                        viewModel.clearDotnet(it)
                        Toast.makeText(this, getString(R.string.done), Toast.LENGTH_SHORT).show()
                    }
                )

                val crash = uiState.pendingCrash
                if (crash != null) {
                    CrashRecoveryDialog(
                        packageName = crash.packageName,
                        onExport = {
                            viewModel.dismissCrashDialog()
                            exportCrashDiagnostics(crash)
                        },
                        onDismiss = {
                            viewModel.dismissCrashDialog()
                        }
                    )
                }
            } // BepInExTheme
        } // setContent
    }

    private fun checkStoragePermission() {
        // Every path this launcher touches is app-specific storage:
        // BepInExPaths.getGameRootDir() goes through Context.getExternalFilesDir()
        // and falls back to filesDir, and modpack import/export use SAF pickers
        // that grant access to exactly the file the user chose. None of that needs
        // a manifest permission, and MANAGE_EXTERNAL_STORAGE is no longer declared
        // (see AndroidManifest.xml), so there is nothing left to ask for.
        //
        // This used to branch: a test build skipped the check because it resolved
        // into app-private storage, while a production build still read and wrote
        // the public /sdcard/BepInEx_Android layout and so requested all-files
        // access. That production layout is gone - BepInExPaths no longer produces
        // it - which left the branch as dead code that could only ask for access it
        // had no use for. It is kept as a function so the call site and the
        // launcher callback stay meaningful, but it no longer gates anything.
        BepInExLog.i("No storage permission required; paths are app-specific")
        viewModel.startGameDetection()
    }

    override fun onPause() {
        leftLauncher = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (leftLauncher) {
            CrashDiagnostics.clearPending(this)
            viewModel.dismissCrashDialog()
            leftLauncher = false
        }
    }

    override fun onDestroy() {
        BepInExLogReader.stopWatching()
        super.onDestroy()
    }

    private var lastLaunchTime = 0L

    private fun launchGame(
        selectedGame: GameDetector.DetectedGame?,
        modpackNames: Set<String>,
        isFrameworkReady: Boolean,
        isExtracting: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchTime < 2000L) {
            return
        }
        lastLaunchTime = now

        val game = selectedGame ?: return

        if (!isFrameworkReady) {
            Toast.makeText(this, getString(R.string.launch_wait_extraction), Toast.LENGTH_SHORT).show()
            if (!isExtracting) viewModel.startExtraction(game.packageName)
            return
        }

        BepInExLog.i("=== Launching ${game.label} (modpacks: ${if (modpackNames.isEmpty()) "vanilla" else modpackNames.joinToString(", ")}) via BootstrapActivity ===")

        // Per-game DataStore reads do file I/O; resolve them off Main, then start.
        // All game-behavior flags travel via Intent extras because the :game
        // process must never open the default process's DataStore file.
        lifecycleScope.launch {
            val useOriginalLibUnity = withContext(Dispatchers.IO) {
                !AppSettings.isUseUnstrippedLibUnityFlow(this@MainActivity, game.packageName).first()
            }
            val blockUnityKill = withContext(Dispatchers.IO) {
                AppSettings.isUnityKillBlockEnabledFlow(this@MainActivity, game.packageName).first()
            }
            val screenDimEnabled = withContext(Dispatchers.IO) {
                AppSettings.getScreenDimInGameEnabledFlow(this@MainActivity, game.packageName).first()
            }
            val floatingLogEnabled = withContext(Dispatchers.IO) {
                AppSettings.getFloatingLogInGameEnabledFlow(this@MainActivity, game.packageName).first()
            }
            // Persist the selection before starting: sequential in this coroutine,
            // so the game can never start before the save completes.
            withContext(Dispatchers.IO) {
                AppSettings.setLastLaunchedModpacks(this@MainActivity, game.packageName, modpackNames)
            }
            try {
                // Snapshot the launch exactly as it is about to be sent:
                // the same names, in the same order as the intent extras
                // below, so the pending record describes this launch even
                // if a later one replaces the active state before export.
                CrashDiagnostics.markLaunch(this@MainActivity, game.packageName, modpackNames.toList())
                val intent = Intent(this@MainActivity, BootstrapActivity::class.java).apply {
                    putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, game.packageName)
                    putExtra(BootstrapActivity.EXTRA_USE_ORIGINAL_LIBUNITY, useOriginalLibUnity)
                    putExtra(BootstrapActivity.EXTRA_BLOCK_UNITY_KILL, blockUnityKill)
                    putExtra(BootstrapActivity.EXTRA_SCREEN_DIM_ENABLED, screenDimEnabled)
                    putExtra(BootstrapActivity.EXTRA_FLOATING_LOG_ENABLED, floatingLogEnabled)
                    if (modpackNames.isNotEmpty()) {
                        putStringArrayListExtra(BootstrapActivity.EXTRA_ACTIVE_MODPACKS, ArrayList(modpackNames))
                        putExtra(BootstrapActivity.EXTRA_ACTIVE_MODPACK, modpackNames.first())
                    }
                }
                startActivity(intent)
            } catch (e: Exception) {
                BepInExLog.e("Launch failed", e)
                Toast.makeText(this@MainActivity, getString(R.string.launch_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportCrashDiagnostics(pending: CrashDiagnostics.PendingLaunch) {
        val packageName = pending.packageName
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = CrashDiagnostics.export(this@MainActivity, pending)
                CrashDiagnostics.clearPending(this@MainActivity)
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.provider",
                        file
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "BepInEx diagnostics: $packageName")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        // ClipData, not EXTRA_STREAM, is what grants the chooser
                        // read access. Without it the sheet opens and cannot read
                        // the file. See LogViewerScreen for the measured log.
                        clipData = android.content.ClipData.newRawUri(null, uri)
                    }
                    startActivity(Intent.createChooser(share, getString(R.string.crash_export)))
                }
            } catch (e: Exception) {
                BepInExLog.e("Failed to export diagnostics", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.crash_export_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
