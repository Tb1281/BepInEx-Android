package com.bepinex.cfgparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CfgDefaultsTest {

    private val sample = """
        [S]

        ## A count.
        # Setting type: Int32
        # Default value: 5
        Count = 99

        ## Empty default.
        # Setting type: String
        # Default value:
        Text = something

        Plain = no default here
    """.trimIndent()

    @Test
    fun `resetToDefault restores the declared default value`() {
        val doc = CfgDocument.parse(sample)
        assertEquals(ValidationResult.Valid, doc.resetToDefault("S", "Count"))
        assertEquals("5", doc.entry("S", "Count")!!.value)
        assertTrue(doc.serialize().contains("Count = 5"))
    }

    @Test
    fun `resetToDefault can restore an explicitly empty default`() {
        val doc = CfgDocument.parse(sample)
        assertEquals(ValidationResult.Valid, doc.resetToDefault("S", "Text"))
        assertEquals("", doc.entry("S", "Text")!!.value)
    }

    @Test
    fun `resetToDefault without declared metadata is invalid and changes nothing`() {
        val doc = CfgDocument.parse(sample)
        val result = doc.resetToDefault("S", "Plain")
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("no default here", doc.entry("S", "Plain")!!.value)
    }

    @Test
    fun `fixture entries expose their declared defaults`() {
        val text = CfgDefaultsTest::class.java.getResourceAsStream("/BepInEx.cfg")!!
            .readBytes().toString(Charsets.UTF_8)
        val doc = CfgDocument.parse(text)

        assertEquals("true", doc.entry("Caching", "EnableAssemblyCache")!!.metadata!!.defaultValue)
        assertEquals("5", doc.entry("Logging.Disk", "ConcurrentFileLimit")!!.metadata!!.defaultValue)
        assertEquals(
            "https://unity.bepinex.dev/libraries/{VERSION}.zip",
            doc.entry("IL2CPP", "UnityBaseLibrariesSource")!!.metadata!!.defaultValue
        )
        // The shipped file's current value can differ from its declared default:
        // ScanMethodRefs declares default true but is currently false.
        val scan = doc.entry("IL2CPP", "ScanMethodRefs")!!
        assertEquals("true", scan.metadata!!.defaultValue)
        assertEquals("false", scan.value)
        // Resetting follows the metadata, not the current value.
        assertEquals(ValidationResult.Valid, scan.let { doc.resetToDefault("IL2CPP", "ScanMethodRefs") })
        assertEquals("true", scan.value)
    }
}
