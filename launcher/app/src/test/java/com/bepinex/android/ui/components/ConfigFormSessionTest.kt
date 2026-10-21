package com.bepinex.android.ui.components

import com.bepinex.cfgparser.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the Framework Config form's edit session, at its
 * public seam: drafts, control edits, resets, search and save blocking.
 * The expected serialised texts are independent literals — the form's
 * contract is that saving touches only the edited value lines.
 */
class ConfigFormSessionTest {

    private fun indexOf(session: ConfigFormSession, key: String): Int =
        session.entries.indexOfFirst { it.key == key }.also { assertTrue("no entry $key", it >= 0) }

    @Test
    fun `unchanged session serialises back to the original text`() {
        val session = ConfigFormSession(SAMPLE)
        assertEquals(SAMPLE, session.serialize())
        assertFalse(session.hasUnsavedChanges())
        assertTrue(session.canSave())
    }

    @Test
    fun `committing an edited draft changes only that value line`() {
        val session = ConfigFormSession(SAMPLE)
        val retries = indexOf(session, "MaxRetries")
        session.setDraft(retries, "7")
        assertTrue(session.commitDrafts().isEmpty())
        assertEquals(SAMPLE.replace("MaxRetries = 5", "MaxRetries = 7"), session.serialize())
        assertEquals("7", session.entries[retries].value)
    }

    @Test
    fun `an invalid draft blocks saving and leaves the document untouched`() {
        val session = ConfigFormSession(SAMPLE)
        val retries = indexOf(session, "MaxRetries")
        session.setDraft(retries, "abc")

        assertFalse(session.canSave())
        val errors = session.blockingErrors()
        assertEquals(1, errors.size)
        assertEquals("MaxRetries", errors[0].first.key)
        assertTrue(errors[0].second.contains("whole number"))

        // Commit attempt reports the same error and applies nothing.
        assertEquals(errors, session.commitDrafts())
        assertEquals(SAMPLE, session.serialize())
        assertEquals("5", session.entries[retries].value)
        assertTrue(session.hasUnsavedChanges())
    }

    @Test
    fun `control values apply immediately and normalise choice spelling`() {
        val session = ConfigFormSession(SAMPLE)
        val outType = indexOf(session, "StandardOutType")
        val result = session.applyValue(outType, "consoleout")
        assertTrue(result is ValidationResult.Valid)
        assertEquals("ConsoleOut", session.entries[outType].value)
        assertTrue(session.serialize().contains("StandardOutType = ConsoleOut"))
        assertTrue(session.hasUnsavedChanges())
    }

    @Test
    fun `toggling multi choice options rebuilds the value in declared order`() {
        val session = ConfigFormSession(SAMPLE)
        val channels = indexOf(session, "LogChannels")

        assertTrue(session.toggleMultiOption(channels, "Debug", true) is ValidationResult.Valid)
        assertEquals("Warn, Error, Debug", session.entries[channels].value)

        assertTrue(session.toggleMultiOption(channels, "Warn", false) is ValidationResult.Valid)
        assertEquals("Error, Debug", session.entries[channels].value)
    }

    @Test
    fun `toggling off the last multi choice option is rejected`() {
        val session = ConfigFormSession(SAMPLE.replace("LogChannels = Warn, Error", "LogChannels = Error"))
        val channels = indexOf(session, "LogChannels")
        val result = session.toggleMultiOption(channels, "Error", false)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Error", session.entries[channels].value)
    }

    @Test
    fun `reset restores the declared default and clears the draft`() {
        val session = ConfigFormSession(SAMPLE)
        val retries = indexOf(session, "MaxRetries")
        session.setDraft(retries, "9")
        session.commitDrafts()
        assertEquals("9", session.entries[retries].value)

        assertTrue(session.reset(retries) is ValidationResult.Valid)
        assertEquals("3", session.entries[retries].value)
        assertNull(session.draft(retries))
        // Back at the file's original value: the session is clean again.
        assertEquals(SAMPLE.replace("MaxRetries = 5", "MaxRetries = 3"), session.serialize())
    }

    @Test
    fun `reset on an entry without a declared default reports why and changes nothing`() {
        val session = ConfigFormSession(SAMPLE)
        val orphaned = indexOf(session, "Orphaned")
        val result = session.reset(orphaned)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("no default"))
        assertEquals("anything goes", session.entries[orphaned].value)
        assertEquals(SAMPLE, session.serialize())
    }

    @Test
    fun `search matches keys and descriptions and includes metadata-less entries`() {
        val session = ConfigFormSession(SAMPLE)
        fun keys(query: String) = session.search(query).map { session.entries[it].key }

        assertEquals(listOf("Enabled"), keys("console window"))
        assertEquals(listOf("MaxRetries"), keys("maxretr"))
        assertEquals(listOf("Orphaned"), keys("orphaned"))
        assertEquals(session.entries.size, session.search("").size)
        assertEquals(session.entries.size, session.search("   ").size)
    }

    @Test
    fun `raw text validation reports invalid entries with reasons`() {
        val problems = ConfigFormSession.validateText(
            SAMPLE.replace("Enabled = true", "Enabled = maybe")
        )
        assertEquals(1, problems.size)
        assertEquals("Enabled", problems[0].first.key)
        assertTrue(problems[0].second.contains("true or false"))

        assertTrue(ConfigFormSession.validateText(SAMPLE).isEmpty())
    }

    @Test
    fun `a draft equal to the current value is not an unsaved change`() {
        val session = ConfigFormSession(SAMPLE)
        val retries = indexOf(session, "MaxRetries")
        session.setDraft(retries, "5")
        assertFalse(session.hasUnsavedChanges())
        session.setDraft(retries, "6")
        assertTrue(session.hasUnsavedChanges())
    }

    private companion object {
        val SAMPLE = """
            [Logging.Console]

            ## If enabled, BepInEx will show a console window.
            # Setting type: Boolean
            # Default value: false
            Enabled = true

            ## Which output stream to use.
            # Setting type: ConsoleOutRedirectType
            # Default value: Auto
            # Acceptable values: Auto, ConsoleOut, StandardOut
            StandardOutType = Auto

            [Harmony.Logger]

            ## Log channels to listen to.
            # Setting type: LogChannel
            # Default value: Warn, Error
            # Acceptable values: None, Info, Warn, Error, Debug, All
            # Multiple values can be set at the same time by separating them with , (e.g. Debug, Warning)
            LogChannels = Warn, Error

            [Custom]

            ## How often to retry.
            # Setting type: Int32
            # Default value: 3
            MaxRetries = 5

            Orphaned = anything goes
        """.trimIndent() + "\n"
    }
}
