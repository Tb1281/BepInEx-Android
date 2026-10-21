package com.bepinex.android.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bepinex_launcher_settings")

/**
 * Game-process state. This file is touched ONLY from the `:game` process
 * (BootstrapActivity's persist/apply bookkeeping). The main settings file
 * above is owned by the default process only: DataStore is not multi-process
 * safe, so the two processes must never open the same file, even though the
 * launcher runs a single game at a time — the default process stays alive
 * while the game runs, so concurrent access is the normal case, not an edge.
 */
val Context.gameStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "bepinex_game_state")

/**
 * Application settings persisted via DataStore.
 *
 * Covers: theme mode, language preference, and settings actions.
 */
object AppSettings {

    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_LANGUAGE = stringPreferencesKey("language")
    private val KEY_LAST_LAUNCHED_GAME = stringPreferencesKey("last_launched_game")

    enum class ThemeMode {
        SYSTEM, DARK, LIGHT;

        companion object {
            fun fromString(value: String?): ThemeMode = when (value) {
                "dark" -> DARK
                "light" -> LIGHT
                else -> SYSTEM  // default: follow system (Material You)
            }
        }
    }

    enum class Language(val key: String) {
        SYSTEM("system"),
        ENGLISH("en"),
        CHINESE("zh"),
        KOREAN("ko"),
        JAPANESE("ja");

        companion object {
            fun fromKey(value: String?): Language = when (value) {
                "en" -> ENGLISH
                "zh" -> CHINESE
                "ko" -> KOREAN
                "ja" -> JAPANESE
                else -> SYSTEM
            }
        }
    }

    // Theme

    fun getThemeModeFlow(context: Context): Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.fromString(prefs[KEY_THEME_MODE])
    }

    suspend fun setThemeMode(context: Context, mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name.lowercase()
        }
    }

    fun applyDayNight(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    // Language

    fun getLanguageFlow(context: Context): Flow<Language> = context.dataStore.data.map { prefs ->
        Language.fromKey(prefs[KEY_LANGUAGE])
    }

    suspend fun setLanguage(context: Context, language: Language) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language.key
        }
    }

    // Floating Log in Game / Screen Dim in Game — per-game Game Options (#36).
    //
    // These used to be single app-wide values under the keys
    // "floating_log_in_game" / "screen_dim_in_game". They are now stored per
    // game under package-suffixed keys, like the other per-game options
    // below. There is deliberately NO migration (decided in #31/#36): the
    // legacy keys are never read again — they only linger as inert values in
    // existing store files — and because the per-game keys are new names,
    // the first read for every game misses and yields the default (off),
    // whatever the old app-wide value was. Isolation is by construction:
    // a value written for one package name can never be read back for
    // another.

    internal fun floatingLogInGameKey(packageName: String) =
        booleanPreferencesKey("floating_log_in_game_$packageName")

    internal fun screenDimInGameKey(packageName: String) =
        booleanPreferencesKey("screen_dim_in_game_$packageName")

    fun getFloatingLogInGameEnabledFlow(context: Context, packageName: String): Flow<Boolean> =
        floatingLogInGameEnabledFlow(context.dataStore, packageName)

    suspend fun setFloatingLogInGameEnabled(context: Context, packageName: String, enabled: Boolean) {
        setFloatingLogInGameEnabled(context.dataStore, packageName, enabled)
    }

    fun getScreenDimInGameEnabledFlow(context: Context, packageName: String): Flow<Boolean> =
        screenDimInGameEnabledFlow(context.dataStore, packageName)

    suspend fun setScreenDimInGameEnabled(context: Context, packageName: String, enabled: Boolean) {
        setScreenDimInGameEnabled(context.dataStore, packageName, enabled)
    }

    // Store-taking cores of the four functions above. The Context versions
    // delegate with the app's settings store; tests pass a temporary store
    // (PreferenceDataStoreFactory) so the DataStore-level contract — legacy
    // keys never surface as per-game values, games stay isolated — is
    // verifiable on the JVM. Production behavior is unchanged.
    internal fun floatingLogInGameEnabledFlow(store: DataStore<Preferences>, packageName: String): Flow<Boolean> =
        store.data.map { prefs ->
            prefs[floatingLogInGameKey(packageName)] ?: false
        }

    internal suspend fun setFloatingLogInGameEnabled(store: DataStore<Preferences>, packageName: String, enabled: Boolean) {
        store.edit { prefs ->
            prefs[floatingLogInGameKey(packageName)] = enabled
        }
    }

    internal fun screenDimInGameEnabledFlow(store: DataStore<Preferences>, packageName: String): Flow<Boolean> =
        store.data.map { prefs ->
            prefs[screenDimInGameKey(packageName)] ?: false
        }

    internal suspend fun setScreenDimInGameEnabled(store: DataStore<Preferences>, packageName: String, enabled: Boolean) {
        store.edit { prefs ->
            prefs[screenDimInGameKey(packageName)] = enabled
        }
    }

    // Last Launched State

    fun getLastLaunchedGameFlow(context: Context): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_LAUNCHED_GAME]
    }

    suspend fun setLastLaunchedGame(context: Context, packageName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_LAUNCHED_GAME] = packageName
        }
    }

    private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    private val KEY_ANIMATION_DISABLED = booleanPreferencesKey("animation_disabled")

    private fun keyBlockUnityKill(packageName: String) = booleanPreferencesKey("block_unity_kill_$packageName")
    private fun keyUseUnstrippedLibUnity(packageName: String) = booleanPreferencesKey("use_unstripped_libunity_$packageName")
    private fun keyActiveModpack(packageName: String) = stringPreferencesKey("active_modpack_$packageName")
    private fun keyActiveModpacks(packageName: String) = stringPreferencesKey("active_modpacks_$packageName")

    fun getDynamicColorFlow(context: Context): Flow<Boolean> = context.dataStore.data.map { prefs ->
        // Default ON: this must agree with the theme's dynamicColor default
        // (and MainUiState's initial value). It used to default to false,
        // which silently overrode the theme default on first run, so a
        // fresh install started with dynamic color off.
        prefs[KEY_DYNAMIC_COLOR] ?: true
    }

    suspend fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_COLOR] = enabled
        }
    }

    fun getAnimationDisabledFlow(context: Context): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ANIMATION_DISABLED] ?: false
    }

    suspend fun setAnimationDisabled(context: Context, disabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ANIMATION_DISABLED] = disabled
        }
    }

    fun isUnityKillBlockEnabledFlow(context: Context, packageName: String): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[keyBlockUnityKill(packageName)] ?: false
        }

    suspend fun setUnityKillBlockEnabled(context: Context, packageName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keyBlockUnityKill(packageName)] = enabled
        }
    }

    fun isUseUnstrippedLibUnityFlow(context: Context, packageName: String): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[keyUseUnstrippedLibUnity(packageName)] ?: false
        }

    suspend fun setUseUnstrippedLibUnity(context: Context, packageName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keyUseUnstrippedLibUnity(packageName)] = enabled
        }
    }

    /**
     * Previously applied modpack, used to persist runtime cfg/logs back to the
     * right owner on the next launch. Backed by [gameStateDataStore], which is
     * `:game`-process-only (see above): the default process never reads or
     * writes this key, so no cross-process DataStore sharing occurs.
     */
    fun getActiveModpackFlow(context: Context, packageName: String): Flow<String?> =
        context.gameStateDataStore.data.map { prefs ->
            prefs[keyActiveModpack(packageName)]?.ifEmpty { null }
        }

    suspend fun setActiveModpack(context: Context, packageName: String, modpackName: String?) {
        context.gameStateDataStore.edit { prefs ->
            prefs[keyActiveModpack(packageName)] = modpackName ?: ""
        }
    }

    /**
     * Full modpack list of the previous run. The single-name key above only
     * remembers the first pack, which made multi-pack runs persist every
     * pack's runtime config into that first pack. Persist now iterates this
     * list and each pack receives only its own plugins' config files.
     */
    fun getActiveModpacksFlow(context: Context, packageName: String): Flow<List<String>> =
        context.gameStateDataStore.data.map { prefs ->
            val joined = prefs[keyActiveModpacks(packageName)]?.ifEmpty { null }
            if (joined != null) {
                joined.split('\n').filter { it.isNotEmpty() }
            } else {
                // Upgrade fallback: only the legacy single-name key exists.
                listOfNotNull(prefs[keyActiveModpack(packageName)]?.ifEmpty { null })
            }
        }

    suspend fun setActiveModpacks(context: Context, packageName: String, modpackNames: List<String>) {
        context.gameStateDataStore.edit { prefs ->
            prefs[keyActiveModpacks(packageName)] = modpackNames.joinToString("\n")
        }
    }

    fun getLastLaunchedModpacksFlow(context: Context, packageName: String): Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val key = stringSetPreferencesKey("last_launched_modpacks_$packageName")
        prefs[key] ?: emptySet()
    }

    suspend fun setLastLaunchedModpacks(context: Context, packageName: String, modpacks: Set<String>) {
        val key = stringSetPreferencesKey("last_launched_modpacks_$packageName")
        context.dataStore.edit { prefs ->
            prefs[key] = modpacks
        }
    }

    // Initialize on startup
    // Note: runBlocking is generally not recommended on UI thread, but for application startup
    // to immediately apply theme/language to prevent flickering, it's often accepted.
    fun initialize(context: Context) {
        runBlocking {
            val prefs = context.dataStore.data.first()
            applyDayNight(ThemeMode.fromString(prefs[KEY_THEME_MODE]))
            applyLanguage(Language.fromKey(prefs[KEY_LANGUAGE]))
        }
    }

    fun applyLanguage(language: Language) {
        val localeTag = when (language) {
            Language.ENGLISH -> "en"
            Language.CHINESE -> "zh-CN"
            Language.KOREAN -> "ko"
            Language.JAPANESE -> "ja"
            Language.SYSTEM -> ""
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(localeTag)
        )
    }
}
