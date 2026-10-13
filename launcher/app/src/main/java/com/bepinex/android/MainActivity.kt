package com.bepinex.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bepinex.android.log.BepInExLogReader
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.navigation.BepInExNavHost
import com.bepinex.android.ui.theme.BepInExTheme
import java.io.File

import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bepinex.android.ui.components.CrashRecoveryDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main launcher Activity -- Compose UI for multi-game BepInEx mod management.
 *
 * On launch, auto-detects installed Unity IL2CPP games via [GameDetector].
 * User selects a game, manages mods, and launches it through [BootstrapActivity].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var leftLauncher = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            viewModel.setFloatingLogInGame(true)
            val pkg = viewModel.uiState.value.selectedGame?.packageName
            if (pkg != null) {
                com.bepinex.android.log.LogOverlayService.start(this, pkg)
            }
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
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
                    floatingLogInGame = uiState.floatingLogInGame,
                    screenDimInGame = uiState.screenDimInGame,
                    onSelectGame = { viewModel.selectGame(it) },
                    onRescan = { viewModel.rescan() },
                    onLaunch = { modpackNames -> launchGame(uiState.selectedGame, modpackNames, uiState.isFrameworkReady, uiState.isExtracting) },
                    onThemeChanged = { viewModel.setTheme(it) },
                    onLanguageChanged = { lang ->
                        viewModel.setLanguage(lang)
                        AppSettings.applyLanguage(lang)
                    },
                    onFloatingLogInGameChanged = { enabled ->
                        if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                overlayPermissionLauncher.launch(intent)
                            } catch (e: Exception) {
                                BepInExLog.e("Failed to open overlay permission settings", e)
                                Toast.makeText(this@MainActivity, "Please grant 'Draw over other apps' in app settings", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            viewModel.setFloatingLogInGame(enabled)
                            if (enabled) {
                                val pkg = uiState.selectedGame?.packageName
                                if (pkg != null) {
                                    com.bepinex.android.log.LogOverlayService.start(this@MainActivity, pkg)
                                }
                            } else {
                                com.bepinex.android.log.LogOverlayService.stop(this@MainActivity)
                            }
                        }
                    },
                    onScreenDimInGameChanged = { enabled ->
                        viewModel.setScreenDimInGame(enabled)
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
                            exportCrashDiagnostics(crash.packageName)
                        },
                        onDismiss = {
                            viewModel.dismissCrashDialog()
                        }
                    )
                }
            }
        }
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

        try {
            CrashDiagnostics.markLaunch(this, game.packageName)
            val intent = Intent(this, BootstrapActivity::class.java).apply {
                putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, game.packageName)
                putExtra(
                    BootstrapActivity.EXTRA_USE_ORIGINAL_LIBUNITY,
                    !AppSettings.isUseUnstrippedLibUnity(this@MainActivity, game.packageName)
                )
                putExtra(
                    BootstrapActivity.EXTRA_BLOCK_UNITY_KILL,
                    AppSettings.isUnityKillBlockEnabled(this@MainActivity, game.packageName)
                )
                if (modpackNames.isNotEmpty()) {
                    putStringArrayListExtra(BootstrapActivity.EXTRA_ACTIVE_MODPACKS, ArrayList(modpackNames))
                    putExtra(BootstrapActivity.EXTRA_ACTIVE_MODPACK, modpackNames.first())
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            BepInExLog.e("Launch failed", e)
            Toast.makeText(this, getString(R.string.launch_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun exportCrashDiagnostics(packageName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = CrashDiagnostics.export(this@MainActivity, packageName)
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
