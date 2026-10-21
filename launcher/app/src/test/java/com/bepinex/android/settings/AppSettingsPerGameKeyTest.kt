package com.bepinex.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Per-game Game Options (#36): the floating log and screen dim options are
 * stored per game, keyed by package name, following the existing per-game
 * key convention in [AppSettings] (e.g. `block_unity_kill_<package>`).
 *
 * These tests pin the storage contract the feature relies on:
 *  - one game's value can never be read or overwritten through another
 *    game's key (isolation by construction, not by convention at call sites);
 *  - the two options for the same game live under different keys;
 *  - the per-game keys are NEW names, distinct from the legacy app-wide
 *    keys (`floating_log_in_game` / `screen_dim_in_game`). There is
 *    deliberately no migration, so the first read of every per-game key
 *    must miss and fall back to the default (off) even when a legacy
 *    app-wide value is still present in the store.
 */
class AppSettingsPerGameKeyTest {

    @Test
    fun `floating log key embeds the package name`() {
        assertEquals(
            "floating_log_in_game_com.example.alpha",
            AppSettings.floatingLogInGameKey("com.example.alpha").name
        )
    }

    @Test
    fun `screen dim key embeds the package name`() {
        assertEquals(
            "screen_dim_in_game_com.example.alpha",
            AppSettings.screenDimInGameKey("com.example.alpha").name
        )
    }

    @Test
    fun `floating log keys are isolated per game`() {
        assertNotEquals(
            AppSettings.floatingLogInGameKey("com.example.alpha").name,
            AppSettings.floatingLogInGameKey("com.example.beta").name
        )
    }

    @Test
    fun `screen dim keys are isolated per game`() {
        assertNotEquals(
            AppSettings.screenDimInGameKey("com.example.alpha").name,
            AppSettings.screenDimInGameKey("com.example.beta").name
        )
    }

    @Test
    fun `floating log and screen dim keys differ for the same game`() {
        assertNotEquals(
            AppSettings.floatingLogInGameKey("com.example.alpha").name,
            AppSettings.screenDimInGameKey("com.example.alpha").name
        )
    }

    @Test
    fun `per-game keys never reuse the legacy app-wide key names`() {
        // The legacy keys held the old app-wide values. If a per-game key
        // ever collided with one of them, the "no migration, defaults on
        // first read" requirement would silently break.
        assertNotEquals("floating_log_in_game", AppSettings.floatingLogInGameKey("com.example.alpha").name)
        assertNotEquals("screen_dim_in_game", AppSettings.screenDimInGameKey("com.example.alpha").name)
        assertNotEquals("floating_log_in_game", AppSettings.floatingLogInGameKey("").name)
        assertNotEquals("screen_dim_in_game", AppSettings.screenDimInGameKey("").name)
    }
}
