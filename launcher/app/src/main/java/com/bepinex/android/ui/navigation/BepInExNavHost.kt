package com.bepinex.android.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.first
import com.bepinex.android.GameDetector
import com.bepinex.android.BepInExPaths
import com.bepinex.android.R
import com.bepinex.android.log.BepInExLogReader
import com.bepinex.android.modpack.ModpackManager
import com.bepinex.android.modpack.ModpackMeta
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.components.ConfigEditorDialog
import com.bepinex.android.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Which modpack an export was requested for.
 *
 * The SAF CreateDocument callback hands back a destination Uri and nothing else,
 * so the modpack being exported has to be carried across the picker. Both the
 * modpack list and the modpack detail screen can start an export, so the identity
 * is remembered rather than assumed from the current route.
 */
private data class ExportTarget(val packageName: String, val modpackName: String)

/**
 * Root navigation host with bottom navigation bar.
 */
@Composable
fun BepInExNavHost(
    // Game state
    detectedGames: List<GameDetector.DetectedGame>,
    selectedGame: GameDetector.DetectedGame?,
    isScanning: Boolean,
    isFrameworkReady: Boolean,
    isExtracting: Boolean,
    extractionStatus: String,
    // Settings state
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    dynamicColor: Boolean,
    animationDisabled: Boolean,
    floatingLogInGame: Boolean,
    screenDimInGame: Boolean,
    // Callbacks
    onSelectGame: (GameDetector.DetectedGame) -> Unit,
    onRescan: () -> Unit,
    onLaunch: (modpackNames: Set<String>) -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationDisabledChanged: (Boolean) -> Unit,
    onFloatingLogInGameChanged: (Boolean) -> Unit,
    onScreenDimInGameChanged: (Boolean) -> Unit,
    onClearBepInEx: (String) -> Unit,
    onClearDotnet: (String) -> Unit
) {
    val navController = rememberNavController()
    val modpackManager = remember { ModpackManager() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val logLines by BepInExLogReader.lines.collectAsStateWithLifecycle()

    // State for modpack list
    var modpacks by remember { mutableStateOf<List<ModpackMeta>>(emptyList()) }
    var activeModpackNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var modpackRefreshKey by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Refresh modpack list when game changes or refresh key bumps.
    // listModpacks() walks the filesystem (and may synthesise a manifest), and
    // the DataStore read/write below does file I/O: keep all of it off Main.
    LaunchedEffect(selectedGame?.packageName, modpackRefreshKey) {
        selectedGame?.let { game ->
            withContext(Dispatchers.IO) {
                modpacks = modpackManager.listModpacks(game.packageName)
                // Save the newly selected game as the default
                AppSettings.setLastLaunchedGame(context, game.packageName)
                activeModpackNames = AppSettings.getLastLaunchedModpacksFlow(context, game.packageName).first()
            }
            isRefreshing = false
        }
    }

    // File picker targets (launchers must live at composable top level).
    // Pickers are fired directly from event handlers below — no Boolean/Int
    // trigger state, which could re-fire on recomposition or desync the target
    // across rotation. The pending target is read by the callback, then cleared.
    var addModTarget by remember { mutableStateOf<String?>(null) }

    // Export is a SAF CreateDocument: the user chooses the destination, so the
    // launcher never needs a storage permission to write the archive. The target
    // is remembered because the picker callback only returns a Uri — it does not
    // say which modpack was being exported, and two screens can trigger one.
    var exportTarget by remember { mutableStateOf<ExportTarget?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            // Must use Activity context, not applicationContext — the URI
            // permission is granted to the Activity that received it.
            coroutineScope.launch(Dispatchers.IO) {
                modpackManager.exportModpackToUri(
                    target.packageName, target.modpackName, uri, context
                )
                withContext(Dispatchers.Main) { modpackRefreshKey++ }
            }
        }
    }

    // Import modpack file picker — inline import to avoid navigation reset
    val importModpackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val game = selectedGame
            if (game != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    // Must use Activity context, not applicationContext — URI permission is on the Activity
                    modpackManager.importModpack(game.packageName, uri, context)
                    withContext(Dispatchers.Main) {
                        modpackRefreshKey++
                    }
                }
            }
        }
    }

    // Add mod to modpack file picker — inline import to avoid navigation reset
    val addModLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetModpack = addModTarget
        addModTarget = null
        if (uri != null && targetModpack != null) {
            val game = selectedGame
            if (game != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    // Must use Activity context — URI permission is on the Activity
                    modpackManager.addModFromUri(context, game.packageName, targetModpack, uri)
                    withContext(Dispatchers.Main) {
                        modpackRefreshKey++
                    }
                }
            }
        }
    }

    // Start watching BepInEx log when game is selected
    LaunchedEffect(selectedGame?.packageName) {
        selectedGame?.let { game ->
            BepInExLogReader.startWatching(game.packageName)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            BottomNavBar(navController = navController, selectedGame = selectedGame)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // Symmetric transitions defined once: every destination animates the
            // same way, including the system predictive-back gesture. The
            // animation-disabled Launcher Setting zeroes the duration, which
            // is what makes that setting take effect in the launcher UI.
            val transitionMillis = if (animationDisabled) 0 else 300
            NavHost(
                navController = navController,
                startDestination = Games,
                enterTransition = { slideInHorizontally(tween(transitionMillis)) { it } + fadeIn(tween(transitionMillis)) },
                exitTransition = { slideOutHorizontally(tween(transitionMillis)) { -it } + fadeOut(tween(transitionMillis)) },
                popEnterTransition = { slideInHorizontally(tween(transitionMillis)) { -it } + fadeIn(tween(transitionMillis)) },
                popExitTransition = { slideOutHorizontally(tween(transitionMillis)) { it } + fadeOut(tween(transitionMillis)) }
            ) {
                // Main game screen
                composable<Games> {
                    GameScreen(
                        detectedGames = detectedGames,
                        selectedGame = selectedGame,
                        isScanning = isScanning,
                        isFrameworkReady = isFrameworkReady,
                        isExtracting = isExtracting,
                        extractionStatus = extractionStatus,
                        activeModpackNames = activeModpackNames,
                        activeModpackModCount = modpacks.filter { it.name in activeModpackNames }.sumOf { it.modCount },
                        onSelectGame = onSelectGame,
                        onRescan = onRescan,
                        // Last-launched modpacks are persisted inside launchGame
                        // before the game starts, so there is no save/start race.
                        onLaunch = { onLaunch(activeModpackNames) },
                        onNavigateToSettings = {
                            // Launcher Settings are global: reachable whether
                            // or not a game is selected.
                            navController.navigateTab(Settings)
                        },
                        onNavigateToModpacks = {
                            selectedGame?.let {
                                navController.navigateTab(Modpacks(it.packageName))
                            }
                        }
                    )
                }

                // Modpack list
                composable<Modpacks> { backStackEntry ->
                    val route = backStackEntry.toRoute<Modpacks>()
                    val packageName = route.packageName
                    var editingGlobalConfig by remember { mutableStateOf<java.io.File?>(null) }
                    // Refresh modpacks (disk walk: off Main)
                    LaunchedEffect(packageName) {
                        withContext(Dispatchers.IO) {
                            modpacks = modpackManager.listModpacks(packageName)
                        }
                    }
                    ModpackListScreen(
                        packageName = packageName,
                        targetGameLabel = selectedGame?.label ?: packageName,
                        modpacks = modpacks,
                        activeModpackNames = activeModpackNames,
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            modpackRefreshKey++
                        },
                        onNavigateBack = { navController.popBackStack() },
                        onCreateModpack = { name ->
                            coroutineScope.launch(Dispatchers.IO) {
                                modpackManager.createModpack(packageName, name)
                                modpacks = modpackManager.listModpacks(packageName)
                            }
                        },
                        onDeleteModpack = { name ->
                            if (activeModpackNames.contains(name)) {
                                activeModpackNames = activeModpackNames - name
                            }
                            coroutineScope.launch(Dispatchers.IO) {
                                modpackManager.deleteModpack(packageName, name)
                                modpacks = modpackManager.listModpacks(packageName)
                            }
                        },
                        onRenameModpack = { oldName, newName ->
                            if (activeModpackNames.contains(oldName)) {
                                activeModpackNames = (activeModpackNames - oldName) + newName
                            }
                            coroutineScope.launch(Dispatchers.IO) {
                                modpackManager.renameModpack(packageName, oldName, newName)
                                modpacks = modpackManager.listModpacks(packageName)
                            }
                        },
                        onSelectModpack = { names ->
                            activeModpackNames = names; coroutineScope.launch(Dispatchers.IO) { AppSettings.setLastLaunchedModpacks(context, packageName, names) }
                        },
                        onOpenModpack = { name ->
                            navController.navigate(ModpackDetail(packageName, name))
                        },
                        onExportModpack = { name ->
                            // SAF CreateDocument: the user picks where the archive
                            // goes. The previous behaviour wrote to a File the app
                            // chose under its own external dir, which needed a
                            // storage permission and then put the export somewhere
                            // the user had to go looking for.
                            exportTarget = ExportTarget(packageName, name)
                            exportLauncher.launch("$name.zip")
                        },
                        onImportModpack = { importModpackLauncher.launch(arrayOf("application/zip", "*/*")) },
                        onEditGlobalConfig = {
                            val file = modpackManager.getFrameworkConfigFile(packageName)
                            if (file.isFile) {
                                editingGlobalConfig = file
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.global_config_not_created),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )

                    // Global framework config editor (single copy in the
                    // runtime config dir - see ModpackManager)
                    editingGlobalConfig?.let { file ->
                        ConfigEditorDialog(
                            configFile = file,
                            onDismiss = { editingGlobalConfig = null },
                            onSave = { f, content ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    f.writeText(content)
                                    withContext(Dispatchers.Main) { editingGlobalConfig = null }
                                }
                            }
                        )
                    }
                }

                // Modpack detail
                composable<ModpackDetail> { backStackEntry ->
                    val route = backStackEntry.toRoute<ModpackDetail>()
                    val packageName = route.packageName
                    val modpackName = route.modpackName

                    var mods by remember { mutableStateOf<List<File>>(emptyList()) }
                    var configFiles by remember { mutableStateOf<List<File>>(emptyList()) }
                    var editingConfig by remember { mutableStateOf<File?>(null) }

                    // Disk walks off Main; refresh key re-runs after add/delete.
                    LaunchedEffect(packageName, modpackName, modpackRefreshKey) {
                        withContext(Dispatchers.IO) {
                            mods = modpackManager.listMods(packageName, modpackName)
                            configFiles = modpackManager.listConfigs(packageName, modpackName)
                        }
                    }

                    ModpackDetailScreen(
                        modpackName = modpackName,
                        mods = mods,
                        configFiles = configFiles,
                        onNavigateBack = { navController.popBackStack() },
                        onAddMod = {
                            addModTarget = modpackName
                            addModLauncher.launch(arrayOf("*/*"))
                        },
                        onDeleteMod = { file ->
                            coroutineScope.launch(Dispatchers.IO) {
                                modpackManager.removeMod(file)
                                mods = modpackManager.listMods(packageName, modpackName)
                            }
                        },
                        onOpenConfig = { configFile ->
                            editingConfig = configFile
                        },
                        onViewLog = { navController.navigate(LogViewer(packageName, modpackName)) },
                        onExportModpack = {
                            // Same SAF path as the list screen: the user picks the
                            // destination, so no storage permission is involved.
                            exportTarget = ExportTarget(packageName, modpackName)
                            exportLauncher.launch("$modpackName.zip")
                        }
                    )

                    // Config editor (disk write off Main)
                    editingConfig?.let { file ->
                        ConfigEditorDialog(
                            configFile = file,
                            onDismiss = { editingConfig = null },
                            onSave = { f, content ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    f.writeText(content)
                                    withContext(Dispatchers.Main) { editingConfig = null }
                                }
                            }
                        )
                    }
                }

                // Settings — a global destination (no route arguments).
                // The per-game maintenance actions still shown on this
                // screen target the currently selected game, exactly as
                // they did when the route carried the package name; with
                // no game selected the screen hides that card (see
                // SettingsScreen). Maintenance moves to the per-game
                // settings screen in #35.
                composable<Settings> {
                    val maintenancePackageName = selectedGame?.packageName
                    SettingsScreen(
                        maintenancePackageName = maintenancePackageName,
                        themeMode = themeMode,
                        language = language,
                        dynamicColor = dynamicColor,
                        animationDisabled = animationDisabled,
                        floatingLogInGame = floatingLogInGame,
                        screenDimInGame = screenDimInGame,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToAbout = { navController.navigate(About) },
                        onThemeChanged = onThemeChanged,
                        onLanguageChanged = onLanguageChanged,
                        onDynamicColorChanged = onDynamicColorChanged,
                        onAnimationDisabledChanged = onAnimationDisabledChanged,
                        onFloatingLogInGameChanged = onFloatingLogInGameChanged,
                        onScreenDimInGameChanged = onScreenDimInGameChanged,
                        onClearBepInEx = { maintenancePackageName?.let(onClearBepInEx) },
                        onClearDotnet = { maintenancePackageName?.let(onClearDotnet) }
                    )
                }

                // Log Viewer
                composable<LogViewer> { backStackEntry ->
                    val route = backStackEntry.toRoute<LogViewer>()
                    val packageName = route.packageName
                    val modpackName = route.modpackName
                    val logFile = BepInExPaths.getModpackLogFile(packageName, modpackName.ifEmpty { null })
                    com.bepinex.android.ui.screens.LogViewerScreen(
                        logFilePath = logFile.absolutePath,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // About
                composable<About> {
                    val context = LocalContext.current
                    val versionName = runCatching {
                        val pm = context.packageManager
                        val info = if (android.os.Build.VERSION.SDK_INT >=
                            android.os.Build.VERSION_CODES.TIRAMISU
                        ) {
                            pm.getPackageInfo(
                                context.packageName,
                                android.content.pm.PackageManager.PackageInfoFlags.of(0)
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getPackageInfo(context.packageName, 0)
                        }
                        info.versionName ?: "unknown"
                    }.getOrDefault("unknown")
                    AboutScreen(
                        versionName = versionName,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

        }
    }
}

/**
 * Top-level tab navigation with back-stack state preservation.
 *
 * popUpTo(startDestination) + saveState/restoreState + launchSingleTop is the
 * official bottom-nav pattern: tab switches keep scroll and UI state instead
 * of destroying the back stack and re-triggering each screen's reload effect.
 */
private inline fun <reified T : Any> NavHostController.navigateTab(route: T) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Bottom bar scoped to its own back-stack collection: only this composable
 * recomposes on navigation, not the whole NavHost. Hidden on drill-in screens
 * (detail, log viewer, about).
 */
@Composable
private fun BottomNavBar(
    navController: NavHostController,
    selectedGame: GameDetector.DetectedGame?
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination

    val showBottomBar = destination?.hasRoute<Games>() == true ||
        destination?.hasRoute<Modpacks>() == true ||
        destination?.hasRoute<Settings>() == true
    if (!showBottomBar) return

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        val isGames = destination?.hasRoute<Games>() == true
        NavigationBarItem(
            selected = isGames,
            // launchSingleTop makes a re-tap a no-op; no explicit guard needed.
            onClick = { navController.navigateTab(Games) },
            icon = {
                Icon(
                    if (isGames) Icons.Filled.SportsEsports else Icons.Outlined.SportsEsports,
                    contentDescription = stringResource(R.string.nav_games)
                )
            },
            label = {
                Text(
                    stringResource(R.string.nav_games),
                    fontWeight = if (isGames) FontWeight.Bold else FontWeight.Normal
                )
            }
        )
        val isModpacks = destination?.hasRoute<Modpacks>() == true
        NavigationBarItem(
            selected = isModpacks,
            onClick = {
                selectedGame?.let { game ->
                    navController.navigateTab(Modpacks(game.packageName))
                }
            },
            enabled = selectedGame != null,
            icon = {
                Icon(
                    if (isModpacks) Icons.Filled.FolderZip else Icons.Outlined.FolderZip,
                    contentDescription = stringResource(R.string.nav_modpacks)
                )
            },
            label = {
                Text(
                    stringResource(R.string.nav_modpacks),
                    fontWeight = if (isModpacks) FontWeight.Bold else FontWeight.Normal
                )
            }
        )
        val isSettings = destination?.hasRoute<Settings>() == true
        NavigationBarItem(
            selected = isSettings,
            // Launcher Settings are global: this tab is always enabled,
            // unlike Modpacks above which needs a selected game.
            onClick = { navController.navigateTab(Settings) },
            icon = {
                Icon(
                    if (isSettings) Icons.Filled.Settings else Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.nav_settings)
                )
            },
            label = {
                Text(
                    stringResource(R.string.nav_settings),
                    fontWeight = if (isSettings) FontWeight.Bold else FontWeight.Normal
                )
            }
        )
    }
}
