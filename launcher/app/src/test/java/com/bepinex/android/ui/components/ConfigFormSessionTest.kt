package com.bepinex.android.ui.components

import com.bepinex.cfgparser.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the Framework Config form's edit session, at its
 * public seam: drafts, control edits, resets, search, save blocking and
 * the form↔raw mode switches.
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

    @Test
    fun `form edits are still modified after switching to raw`() {
        val session = ConfigFormSession(SAMPLE)
        val retries = indexOf(session, "MaxRetries")
        session.setDraft(retries, "7")

        // The switch commits the draft; the raw editor opens on the result.
        assertTrue(session.switchToRaw().isEmpty())
        val rawText = session.serialize()
        assertEquals(SAMPLE.replace("MaxRetries = 5", "MaxRetries = 7"), rawText)

        // Judged against the opened file the carried-over edit is still an
        // unsaved change, so the raw-mode guard and save stay live. (The
        // old rawText != serialize() test compared the edit with itself
        // and saw nothing.)
        assertTrue(session.isModified(rawText))
        // Saving that raw text is exactly saving the form edit.
        assertFalse(session.isModified(SAMPLE))
        assertEquals(rawText, session.serialize())
    }

    @Test
    fun `control edits are still modified after switching to raw`() {
        val session = ConfigFormSession(SAMPLE)
        val enabled = indexOf(session, "Enabled")
        assertTrue(session.applyValue(enabled, "false") is ValidationResult.Valid)

        assertTrue(session.switchToRaw().isEmpty())
        assertTrue(session.isModified(session.serialize()))
    }

    @Test
    fun `switching to raw without edits is not a modification`() {
        val session = ConfigFormSession(SAMPLE)
        assertTrue(session.switchToRaw().isEmpty())
        assertFalse(session.isModified(session.serialize()))
        assertFalse(session.hasUnsavedChanges())
    }

    @Test
    fun `an invalid draft blocks switching to raw and keeps the draft`() {
        val session = ConfigFormSession(SAMPLE)
        val retries = indexOf(session, "MaxRetries")
        session.setDraft(retries, "abc")

        val errors = session.switchToRaw()
        assertEquals(1, errors.size)
        assertEquals("MaxRetries", errors[0].first.key)

        // Nothing was committed and the input survives for fixing.
        assertEquals(SAMPLE, session.serialize())
        assertEquals("abc", session.draft(retries))
    }

    @Test
    fun `switching back to the form re-parses raw edits and discards drafts`() {
        val session = ConfigFormSession(SAMPLE)
        val retries = indexOf(session, "MaxRetries")
        session.setDraft(retries, "9")

        val rawEdited = SAMPLE.replace("Enabled = true", "Enabled = false")
        session.switchToForm(rawEdited)

        // Drafts described the old document: gone, not applied on top.
        assertNull(session.draft(retries))
        assertEquals("5", session.entries[retries].value)
        // The document is the raw text now, re-parsed.
        val enabled = indexOf(session, "Enabled")
        assertEquals("false", session.entries[enabled].value)
        assertEquals(rawEdited, session.serialize())
        // Still judged against the file the session was opened with.
        assertTrue(session.hasUnsavedChanges())
    }

    @Test
    fun `switching back to the form with the original text is clean`() {
        val session = ConfigFormSession(SAMPLE)
        assertTrue(session.switchToRaw().isEmpty())
        session.switchToForm(SAMPLE)
        assertEquals(SAMPLE, session.serialize())
        assertFalse(session.hasUnsavedChanges())
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
