package com.bepinex.android.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bepinex_launcher_settings")

/**
 * Application settings persisted via DataStore.
 *
 * Covers: theme mode, language preference, and settings actions.
 */
object AppSettings {

    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_LANGUAGE = stringPreferencesKey("language")
    private val KEY_FLOATING_LOG_IN_GAME = booleanPreferencesKey("floating_log_in_game")
    private val KEY_SCREEN_DIM_IN_GAME = booleanPreferencesKey("screen_dim_in_game")
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

    // Floating Log in Game

    fun getFloatingLogInGameEnabledFlow(context: Context): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FLOATING_LOG_IN_GAME] ?: false
    }

    suspend fun setFloatingLogInGameEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FLOATING_LOG_IN_GAME] = enabled
        }
    }

    // Screen Dim in Game

    fun getScreenDimInGameEnabledFlow(context: Context): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SCREEN_DIM_IN_GAME] ?: false
    }

    suspend fun setScreenDimInGameEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCREEN_DIM_IN_GAME] = enabled
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

    fun isFloatingLogInGameEnabled(context: Context): Boolean = runBlocking {
        context.dataStore.data.first()[KEY_FLOATING_LOG_IN_GAME] ?: false
    }

    fun isDynamicColorEnabled(context: Context): Boolean = runBlocking {
        context.dataStore.data.first()[KEY_DYNAMIC_COLOR] ?: false
    }

    suspend fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_COLOR] = enabled
        }
    }

    fun isAnimationDisabled(context: Context): Boolean = runBlocking {
        context.dataStore.data.first()[KEY_ANIMATION_DISABLED] ?: false
    }

    suspend fun setAnimationDisabled(context: Context, disabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ANIMATION_DISABLED] = disabled
        }
    }

    fun isUnityKillBlockEnabled(context: Context, packageName: String): Boolean = runBlocking {
        context.dataStore.data.first()[keyBlockUnityKill(packageName)] ?: false
    }

    suspend fun setUnityKillBlockEnabled(context: Context, packageName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keyBlockUnityKill(packageName)] = enabled
        }
    }

    fun isUseUnstrippedLibUnity(context: Context, packageName: String): Boolean = runBlocking {
        context.dataStore.data.first()[keyUseUnstrippedLibUnity(packageName)] ?: false
    }

    suspend fun setUseUnstrippedLibUnity(context: Context, packageName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keyUseUnstrippedLibUnity(packageName)] = enabled
        }
    }

    fun getActiveModpack(context: Context, packageName: String): String? = runBlocking {
        context.dataStore.data.first()[keyActiveModpack(packageName)]?.ifEmpty { null }
    }

    suspend fun setActiveModpack(context: Context, packageName: String, modpackName: String?) {
        context.dataStore.edit { prefs ->
            prefs[keyActiveModpack(packageName)] = modpackName ?: ""
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
            // Language is applied to Compose via a localized CompositionLocal in
            // MainActivity to avoid AppCompat locale-triggered recreation/freezes.
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

    /**
     * Converts a stored language preference to a concrete Locale for contexts that
     * are not AppCompat activities (e.g. ComponentActivity + Jetpack Compose),
     * where AppCompatDelegate.setApplicationLocales alone does not reconfigure
     * the activity's resources.
     */
    fun toLocale(language: Language): Locale? = when (language) {
        Language.ENGLISH -> Locale("en")
        Language.CHINESE -> Locale("zh", "CN")
        Language.KOREAN -> Locale("ko")
        Language.JAPANESE -> Locale("ja")
        Language.SYSTEM -> null
    }
}
