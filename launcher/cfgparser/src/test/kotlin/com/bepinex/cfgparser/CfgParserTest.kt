package com.bepinex.cfgparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CfgParserTest {

    private val sample = """
        [Caching]

        ## Enable the cache.
        ## Costs a little memory.
        # Setting type: Boolean
        # Default value: true
        EnableCache = false

        [Logging]

        ## Levels to write.
        # Setting type: LogLevel
        # Default value: Warning, Info
        # Acceptable values: None, Warning, Info, Debug
        # Multiple values can be set at the same time by separating them with , (e.g. Debug, Warning)
        LogLevels = Warning

        ## How many files to keep.
        # Setting type: Int32
        # Default value: 5
        FileLimit = 3

        ## A display name.
        # Setting type: String
        # Default value: hello
        Name = world

        ## Pick one backend.
        # Setting type: Backend
        # Default value: auto
        # Acceptable values: auto, fast, safe
        Backend = fast

        PlainEntry = anything goes
    """.trimIndent()

    @Test
    fun `sections and entries are exposed in file order with current values`() {
        val doc = CfgDocument.parse(sample)

        assertEquals(listOf("Caching", "Logging"), doc.sections)
        assertEquals(
            listOf("EnableCache", "LogLevels", "FileLimit", "Name", "Backend", "PlainEntry"),
            doc.entries.map { it.key }
        )
        assertEquals("Logging", doc.entry("Logging", "FileLimit")!!.section)
        assertEquals("3", doc.entry("Logging", "FileLimit")!!.value)
        assertNull(doc.entry("Logging", "NoSuchKey"))
    }

    @Test
    fun `boolean entry metadata is extracted from the comment header`() {
        val entry = CfgDocument.parse(sample).entry("Caching", "EnableCache")!!

        assertEquals(CfgValueKind.BOOLEAN, entry.kind)
        assertEquals("Enable the cache.\nCosts a little memory.", entry.metadata!!.description)
        assertEquals("Boolean", entry.metadata!!.typeName)
        assertEquals("true", entry.metadata!!.defaultValue)
        assertTrue(entry.metadata!!.acceptableValues.isEmpty())
        assertFalse(entry.metadata!!.allowsMultipleValues)
    }

    @Test
    fun `multi-value entry exposes acceptable values and the multiple flag`() {
        val entry = CfgDocument.parse(sample).entry("Logging", "LogLevels")!!

        assertEquals(CfgValueKind.MULTI_CHOICE, entry.kind)
        assertEquals(listOf("None", "Warning", "Info", "Debug"), entry.metadata!!.acceptableValues)
        assertTrue(entry.metadata!!.allowsMultipleValues)
        assertEquals("Warning, Info", entry.metadata!!.defaultValue)
    }

    @Test
    fun `integer string and single-choice kinds are derived from metadata`() {
        val doc = CfgDocument.parse(sample)

        assertEquals(CfgValueKind.INTEGER, doc.entry("Logging", "FileLimit")!!.kind)
        assertEquals("5", doc.entry("Logging", "FileLimit")!!.metadata!!.defaultValue)
        assertEquals(CfgValueKind.STRING, doc.entry("Logging", "Name")!!.kind)
        val backend = doc.entry("Logging", "Backend")!!
        assertEquals(CfgValueKind.SINGLE_CHOICE, backend.kind)
        assertEquals(listOf("auto", "fast", "safe"), backend.metadata!!.acceptableValues)
        assertFalse(backend.metadata!!.allowsMultipleValues)
    }

    @Test
    fun `entry without a comment header has no metadata and is a plain string entry`() {
        val entry = CfgDocument.parse(sample).entry("Logging", "PlainEntry")!!

        assertNull(entry.metadata)
        assertEquals(CfgValueKind.UNTYPED, entry.kind)
        assertEquals("anything goes", entry.value)
    }
}
