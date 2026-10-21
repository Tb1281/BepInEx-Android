package com.bepinex.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bepinex.android.settings.AppSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

data class MainUiState(
    val detectedGames: List<GameDetector.DetectedGame> = emptyList(),
    val selectedGame: GameDetector.DetectedGame? = null,
    val isScanning: Boolean = true,
    val isExtracting: Boolean = false,
    val extractionStatus: String = "",
    val isFrameworkReady: Boolean = false,
    val themeMode: AppSettings.ThemeMode = AppSettings.ThemeMode.SYSTEM,
    val language: AppSettings.Language = AppSettings.Language.SYSTEM,
    val dynamicColor: Boolean = true,
    val animationDisabled: Boolean = false,
    val pendingCrash: CrashDiagnostics.PendingLaunch? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val fileExtractor = FileExtractor(application)

    init {
        checkPendingCrash()
        viewModelScope.launch {
            AppSettings.getThemeModeFlow(application).collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            AppSettings.getLanguageFlow(application).collect { lang ->
                _uiState.update { it.copy(language = lang) }
            }
        }
        viewModelScope.launch {
            AppSettings.getDynamicColorFlow(application).collect { enabled ->
                _uiState.update { it.copy(dynamicColor = enabled) }
            }
        }
        viewModelScope.launch {
            AppSettings.getAnimationDisabledFlow(application).collect { disabled ->
                _uiState.update { it.copy(animationDisabled = disabled) }
            }
        }
    }

    fun checkPendingCrash() {
        val pending = CrashDiagnostics.pending(getApplication())
        _uiState.update { it.copy(pendingCrash = pending) }
    }

    fun dismissCrashDialog() {
        CrashDiagnostics.clearPending(getApplication())
        _uiState.update { it.copy(pendingCrash = null) }
    }

    fun startGameDetection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }

            try {
                val games = GameDetector.detectGames(getApplication())
                BepInExLog.i("Detected ${games.size} Unity IL2CPP game(s)")

                val currentSelected = _uiState.value.selectedGame
                // DataStore read on IO; viewModelScope is Main-bound.
                val lastLaunchedPkg = withContext(Dispatchers.IO) {
                    AppSettings.getLastLaunchedGameFlow(getApplication()).first()
                }
                val newSelected = currentSelected 
                    ?: games.find { it.packageName == lastLaunchedPkg } 
                    ?: games.firstOrNull()

                _uiState.update { 
                    it.copy(
                        detectedGames = games,
                        selectedGame = newSelected,
                        isFrameworkReady = newSelected?.let { game -> fileExtractor.isFrameworkReady(game.packageName) } ?: false
                    )
                }

                if (newSelected != null && !fileExtractor.isFrameworkReady(newSelected.packageName)) {
                    startExtraction(newSelected.packageName)
                }
            } catch (e: Exception) {
                BepInExLog.e("Game detection failed", e)
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun selectGame(game: GameDetector.DetectedGame) {
        BepInExLog.i("Selected: ${game.label} (${game.packageName})")
        val ready = fileExtractor.isFrameworkReady(game.packageName)
        
        _uiState.update {
            it.copy(
                selectedGame = game,
                isFrameworkReady = ready
            )
        }

        if (!ready) {
            startExtraction(game.packageName)
        }
    }

    fun rescan() {
        GameDetector.invalidateCache()
        startGameDetection()
    }

    fun startExtraction(packageName: String) {
        _uiState.update { 
            it.copy(
                isExtracting = true,
                extractionStatus = getApplication<Application>().getString(R.string.extracting)
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                fileExtractor.extractBepInExIfNeeded(packageName) { status ->
                    _uiState.update { it.copy(extractionStatus = status) }
                }
                fileExtractor.extractDotnetIfNeeded(packageName) { status ->
                    _uiState.update { it.copy(extractionStatus = status) }
                }
                BepInExLog.i("Framework extraction complete for $packageName")
            } catch (e: Exception) {
                BepInExLog.e("Extraction failed", e)
            }
            withContext(Dispatchers.Main) {
                _uiState.update { 
                    it.copy(
                        isExtracting = false,
                        extractionStatus = "",
                        isFrameworkReady = fileExtractor.isFrameworkReady(packageName)
                    )
                }
            }
        }
    }

    fun setTheme(mode: AppSettings.ThemeMode) {
        viewModelScope.launch {
            AppSettings.setThemeMode(getApplication(), mode)
            AppSettings.applyDayNight(mode)
        }
    }

    fun setLanguage(lang: AppSettings.Language) {
        viewModelScope.launch {
            AppSettings.setLanguage(getApplication(), lang)
            AppSettings.applyLanguage(lang)
        }
    }

    // Floating log / screen dim are per-game Game Options (#36): the caller
    // (Game Options screen, via MainActivity's permission flow) always names
    // the game the value belongs to. The Game Options screen observes the
    // values straight from AppSettings for its route's package, so they are
    // not mirrored into MainUiState.
    fun setFloatingLogInGame(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            AppSettings.setFloatingLogInGameEnabled(getApplication(), packageName, enabled)
        }
    }

    fun setScreenDimInGame(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            AppSettings.setScreenDimInGameEnabled(getApplication(), packageName, enabled)
        }
    }

    fun clearBepInEx(packageName: String) {
        // Recursive delete of the framework tree can take seconds; keep it off Main.
        viewModelScope.launch(Dispatchers.IO) {
            val dir = BepInExPaths.getBepInExDir(packageName)
            if (dir.exists()) {
                dir.deleteRecursively()
                BepInExLog.i("Cleared BepInEx: ${dir.absolutePath}")
            }
            _uiState.update {
                it.copy(isFrameworkReady = fileExtractor.isFrameworkReady(packageName))
            }
        }
    }

    fun clearDotnet(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dotnetDir = BepInExPaths.getDotnetDir(packageName)
            val dataDir = BepInExPaths.getCopiedDataDir(packageName)
            if (dotnetDir.exists()) dotnetDir.deleteRecursively()
            if (dataDir.exists()) dataDir.deleteRecursively()
            BepInExLog.i("Cleared .NET data for $packageName")
        }
    }

    fun clearLibUnity(packageName: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val appDataDir = BepInExPaths.getAppDataDir(packageName)
            val libunityDir = File(appDataDir, "libunity")
            val cleared = if (libunityDir.exists()) {
                libunityDir.deleteRecursively()
                BepInExLog.i("Cleared libunity cache: ${libunityDir.absolutePath}")
                true
            } else {
                BepInExLog.i("Clear libunity: nothing to clear at ${libunityDir.absolutePath}")
                false
            }
            withContext(Dispatchers.Main) { onDone(cleared) }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            AppSettings.setDynamicColorEnabled(getApplication(), enabled)
            _uiState.update { it.copy(dynamicColor = enabled) }
        }
    }

    fun setAnimationDisabled(disabled: Boolean) {
        viewModelScope.launch {
            AppSettings.setAnimationDisabled(getApplication(), disabled)
            _uiState.update { it.copy(animationDisabled = disabled) }
        }
    }
}
