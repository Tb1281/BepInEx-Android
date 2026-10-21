package com.bepinex.android.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-game Game Options (#36) at the DataStore level, complementing
 * [AppSettingsPerGameKeyTest] (which pins only the key names).
 *
 * The acceptance criteria these verify are behavioral:
 *  - "first read is the default": a store file written by the old app —
 *    where the floating log / screen dim options were single app-wide
 *    values under `floating_log_in_game` / `screen_dim_in_game` — must
 *    yield the per-game default (off) for every game. There is deliberately
 *    no migration, so the legacy values must never surface.
 *  - isolation: a value written for one game is invisible to every other
 *    game, and the two options for one game are independent.
 *
 * Runs against a real Preferences DataStore over a temporary file (created
 * via the store-taking cores in [AppSettings], which the Context-based
 * production API delegates to).
 */
class AppSettingsPerGameStoreTest {

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("appsettings-per-game-test").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempDir, "settings.preferences_pb")
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `legacy app-wide values never surface as per-game values`() = runBlocking {
        // A store file as the pre-#36 app left it: both options on, app-wide.
        store.edit { prefs ->
            prefs[booleanPreferencesKey("floating_log_in_game")] = true
            prefs[booleanPreferencesKey("screen_dim_in_game")] = true
        }

        assertFalse(AppSettings.floatingLogInGameEnabledFlow(store, "com.example.alpha").first())
        assertFalse(AppSettings.screenDimInGameEnabledFlow(store, "com.example.alpha").first())
        assertFalse(AppSettings.floatingLogInGameEnabledFlow(store, "com.example.beta").first())
        assertFalse(AppSettings.screenDimInGameEnabledFlow(store, "com.example.beta").first())
    }

    @Test
    fun `floating log value written for one game is invisible to other games`() = runBlocking {
        AppSettings.setFloatingLogInGameEnabled(store, "com.example.alpha", true)

        assertTrue(AppSettings.floatingLogInGameEnabledFlow(store, "com.example.alpha").first())
        assertFalse(AppSettings.floatingLogInGameEnabledFlow(store, "com.example.beta").first())
        // The other option for the same game is independent.
        assertFalse(AppSettings.screenDimInGameEnabledFlow(store, "com.example.alpha").first())
    }

    @Test
    fun `screen dim value written for one game is invisible to other games`() = runBlocking {
        AppSettings.setScreenDimInGameEnabled(store, "com.example.beta", true)

        assertTrue(AppSettings.screenDimInGameEnabledFlow(store, "com.example.beta").first())
        assertFalse(AppSettings.screenDimInGameEnabledFlow(store, "com.example.alpha").first())
        // The other option for the same game is independent.
        assertFalse(AppSettings.floatingLogInGameEnabledFlow(store, "com.example.beta").first())
    }

    @Test
    fun `per-game value can be turned back off`() = runBlocking {
        AppSettings.setFloatingLogInGameEnabled(store, "com.example.alpha", true)
        AppSettings.setFloatingLogInGameEnabled(store, "com.example.alpha", false)

        assertFalse(AppSettings.floatingLogInGameEnabledFlow(store, "com.example.alpha").first())
    }
}
