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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
    floatingLogInGame: Boolean,
    screenDimInGame: Boolean,
    // Callbacks
    onSelectGame: (GameDetector.DetectedGame) -> Unit,
    onRescan: () -> Unit,
    onLaunch: (modpackNames: Set<String>) -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
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
            // same way, including the system predictive-back gesture.
            NavHost(
                navController = navController,
                startDestination = NavRoutes.GAMES,
                enterTransition = { slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) },
                exitTransition = { slideOutHorizontally(tween(300)) { -it } + fadeOut(tween(300)) },
                popEnterTransition = { slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300)) },
                popExitTransition = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)) }
            ) {
                // Main game screen
                composable(
                    route = NavRoutes.GAMES
                ) {
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
                            selectedGame?.let {
                                navController.navigateTab(NavRoutes.settings(it.packageName))
                            }
                        },
                        onNavigateToModpacks = {
                            selectedGame?.let {
                                navController.navigateTab(NavRoutes.modpacks(it.packageName))
                            }
                        }
                    )
                }

                // Modpack list
                composable(
                    route = NavRoutes.MODPACKS,
                    arguments = listOf(navArgument("packageName") { type = NavType.StringType })
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
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
                            navController.navigate(NavRoutes.modpackDetail(packageName, name))
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
                        onImportModpack = { importModpackLauncher.launch(arrayOf("application/zip", "*/*")) }
                    )
                }

                // Modpack detail
                composable(
                    route = NavRoutes.MODPACK_DETAIL,
                    arguments = listOf(
                        navArgument("packageName") { type = NavType.StringType },
                        navArgument("modpackName") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    val modpackName = backStackEntry.arguments?.getString("modpackName") ?: return@composable

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
                        onViewLog = { navController.navigate(NavRoutes.logViewer(packageName, modpackName)) },
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

                // Settings
                composable(
                    route = NavRoutes.SETTINGS,
                    arguments = listOf(navArgument("packageName") { type = NavType.StringType })
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    val settingsContext = LocalContext.current
                    SettingsScreen(
                        packageName = packageName,
                        themeMode = themeMode,
                        language = language,
                        floatingLogInGame = floatingLogInGame,
                        screenDimInGame = screenDimInGame,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) },
                        onThemeChanged = onThemeChanged,
                        onLanguageChanged = onLanguageChanged,
                        onFloatingLogInGameChanged = onFloatingLogInGameChanged,
                        onScreenDimInGameChanged = onScreenDimInGameChanged,
                        onClearBepInEx = { onClearBepInEx(packageName) },
                        onClearDotnet = { onClearDotnet(packageName) }
                    )
                }

                // Log Viewer
                composable(
                    route = NavRoutes.LOG_VIEWER,
                    arguments = listOf(
                        navArgument("packageName") { type = NavType.StringType },
                        navArgument("modpackName") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    val modpackName = backStackEntry.arguments?.getString("modpackName") ?: ""
                    val logFile = BepInExPaths.getModpackLogFile(packageName, modpackName.ifEmpty { null })
                    com.bepinex.android.ui.screens.LogViewerScreen(
                        logFilePath = logFile.absolutePath,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // About
                composable(
                    route = NavRoutes.ABOUT
                ) {
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
private fun NavHostController.navigateTab(route: String) {
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
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in listOf(
        NavRoutes.GAMES, "modpacks/{packageName}", "settings/{packageName}"
    )
    if (!showBottomBar) return

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        val isGames = currentRoute == NavRoutes.GAMES
        NavigationBarItem(
            selected = isGames,
            // launchSingleTop makes a re-tap a no-op; no explicit guard needed.
            onClick = { navController.navigateTab(NavRoutes.GAMES) },
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
        val isModpacks = currentRoute == "modpacks/{packageName}"
        NavigationBarItem(
            selected = isModpacks,
            onClick = {
                selectedGame?.let { game ->
                    navController.navigateTab(NavRoutes.modpacks(game.packageName))
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
        val isSettings = currentRoute == "settings/{packageName}"
        NavigationBarItem(
            selected = isSettings,
            onClick = {
                selectedGame?.let { game ->
                    navController.navigateTab(NavRoutes.settings(game.packageName))
                }
            },
            enabled = selectedGame != null,
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
