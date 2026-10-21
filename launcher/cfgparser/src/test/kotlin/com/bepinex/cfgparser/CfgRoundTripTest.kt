package com.bepinex.cfgparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CfgRoundTripTest {

    private fun fixtureText(): String =
        CfgRoundTripTest::class.java.getResourceAsStream("/BepInEx.cfg")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `parsing and serialising the framework config without changes is byte-identical`() {
        val text = fixtureText()
        assertEquals(text, CfgDocument.parse(text).serialize())
    }

    @Test
    fun `changing values rewrites only the changed value lines in the framework config`() {
        val text = fixtureText()
        val doc = CfgDocument.parse(text)

        val boolResult = doc.setValue("Caching", "EnableAssemblyCache", "false")
        val intResult = doc.setValue("Logging.Disk", "ConcurrentFileLimit", "9")
        assertEquals(ValidationResult.Valid, boolResult)
        assertEquals(ValidationResult.Valid, intResult)

        val originalLines = text.split("\n")
        val newLines = doc.serialize().split("\n")
        assertEquals(originalLines.size, newLines.size)
        val changed = originalLines.indices.filter { originalLines[it] != newLines[it] }
        assertEquals(2, changed.size)
        assertEquals("EnableAssemblyCache = false", newLines[changed[0]])
        assertEquals("ConcurrentFileLimit = 9", newLines[changed[1]])
    }

    @Test
    fun `round trip of a small document preserves comments blank lines and unknown lines`() {
        val text = "# a stray header comment\n" +
            "\n" +
            "[S]\n" +
            "\n" +
            "## Desc.\n" +
            "# Setting type: Boolean\n" +
            "# Default value: true\n" +
            "Flag = true\n" +
            "\n" +
            "Mystery = keep me\n" +
            "a line without an equals sign\n"

        val doc = CfgDocument.parse(text)
        assertEquals(ValidationResult.Valid, doc.setValue("S", "Flag", "false"))
        val out = doc.serialize()

        assertNotEquals(text, out)
        assertEquals(text.replace("Flag = true", "Flag = false"), out)
    }

    @Test
    fun `document without trailing newline round-trips exactly`() {
        val text = "[S]\nKey = value"
        val doc = CfgDocument.parse(text)
        assertEquals(text, doc.serialize())
        assertEquals(ValidationResult.Valid, doc.setValue("S", "Key", "other"))
        assertEquals("[S]\nKey = other", doc.serialize())
    }
}
