package com.bepinex.cfgparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CfgValidationTest {

    private val sample = """
        [S]

        ## A switch.
        # Setting type: Boolean
        # Default value: true
        Flag = true

        ## A count.
        # Setting type: Int32
        # Default value: 5
        Count = 5

        ## A ratio.
        # Setting type: Double
        # Default value: 0.5
        Ratio = 0.5

        ## Pick one.
        # Setting type: Backend
        # Default value: auto
        # Acceptable values: auto, fast, safe
        Backend = auto

        ## Pick any.
        # Setting type: LogLevel
        # Default value: Warning
        # Acceptable values: None, Warning, Info, Debug
        # Multiple values can be set at the same time by separating them with , (e.g. Debug, Warning)
        Levels = Warning

        ## Free text.
        # Setting type: String
        # Default value:
        Text = abc

        Plain = whatever
    """.trimIndent()

    private fun doc() = CfgDocument.parse(sample)

    private val integerSample = """
        [S]

        ## Unsigned byte.
        # Setting type: Byte
        # Default value: 0
        B = 0

        ## Signed byte.
        # Setting type: SByte
        # Default value: 0
        SB = 0

        ## Short.
        # Setting type: Int16
        # Default value: 0
        I16 = 0

        ## Unsigned short.
        # Setting type: UInt16
        # Default value: 0
        U16 = 0

        ## Int.
        # Setting type: Int32
        # Default value: 0
        I32 = 0

        ## Unsigned int.
        # Setting type: UInt32
        # Default value: 0
        U32 = 0

        ## Unsigned long.
        # Setting type: UInt64
        # Default value: 0
        U64 = 0
    """.trimIndent()

    private fun assertInvalid(result: ValidationResult) {
        assertTrue("expected Invalid but was $result", result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).reason.isNotBlank())
    }

    @Test
    fun `boolean accepts true and false and rejects other words`() {
        val flag = doc().entry("S", "Flag")!!
        assertEquals(ValidationResult.Valid, flag.validate("true"))
        assertEquals(ValidationResult.Valid, flag.validate("false"))
        assertInvalid(flag.validate("yes"))
        assertInvalid(flag.validate("1"))
        assertInvalid(flag.validate(""))
    }

    @Test
    fun `integer accepts whole numbers and rejects fractions and text`() {
        val count = doc().entry("S", "Count")!!
        assertEquals(ValidationResult.Valid, count.validate("42"))
        assertEquals(ValidationResult.Valid, count.validate("-3"))
        assertInvalid(count.validate("4.5"))
        assertInvalid(count.validate("five"))
        // Int32 range is enforced
        assertInvalid(count.validate("9999999999999"))
    }

    @Test
    fun `decimal accepts fractional numbers and rejects text`() {
        val ratio = doc().entry("S", "Ratio")!!
        assertEquals(ValidationResult.Valid, ratio.validate("1.25"))
        assertEquals(ValidationResult.Valid, ratio.validate("2"))
        assertInvalid(ratio.validate("half"))
    }

    @Test
    fun `single choice accepts a listed value and rejects unlisted ones`() {
        val backend = doc().entry("S", "Backend")!!
        assertEquals(ValidationResult.Valid, backend.validate("fast"))
        val rejected = backend.validate("turbo")
        assertInvalid(rejected)
        assertTrue((rejected as ValidationResult.Invalid).reason.contains("turbo"))
        assertInvalid(backend.validate("fast, safe"))
        assertInvalid(backend.validate(""))
    }

    @Test
    fun `multi choice accepts combinations of listed values and rejects unlisted members`() {
        val levels = doc().entry("S", "Levels")!!
        assertEquals(ValidationResult.Valid, levels.validate("Warning"))
        assertEquals(ValidationResult.Valid, levels.validate("Debug, Info"))
        assertEquals(ValidationResult.Valid, levels.validate("None"))
        val rejected = levels.validate("Warning, Verbose")
        assertInvalid(rejected)
        assertTrue((rejected as ValidationResult.Invalid).reason.contains("Verbose"))
        assertInvalid(levels.validate(""))
    }

    @Test
    fun `string and untyped entries accept any value`() {
        val d = doc()
        assertEquals(ValidationResult.Valid, d.entry("S", "Text")!!.validate("anything at all"))
        assertEquals(ValidationResult.Valid, d.entry("S", "Text")!!.validate(""))
        assertEquals(ValidationResult.Valid, d.entry("S", "Plain")!!.validate("yes, even this"))
    }

    @Test
    fun `setValue refuses an invalid value and leaves the document unchanged`() {
        val d = doc()
        val before = d.serialize()
        assertInvalid(d.setValue("S", "Count", "not-a-number"))
        assertEquals("5", d.entry("S", "Count")!!.value)
        assertEquals(before, d.serialize())
    }

    @Test
    fun `integer ranges are enforced at the boundaries of each declared type`() {
        val d = CfgDocument.parse(integerSample)
        // key, values that must be accepted, values that must be rejected
        val cases = listOf(
            Triple("B", listOf("0", "255"), listOf("256", "-1")),
            Triple("SB", listOf("-128", "127"), listOf("-129", "128")),
            Triple("I16", listOf("-32768", "32767"), listOf("-32769", "32768")),
            Triple("U16", listOf("0", "65535"), listOf("-1", "65536")),
            Triple("I32", listOf("-2147483648", "2147483647"), listOf("-2147483649", "2147483648")),
            Triple("U32", listOf("0", "4294967295"), listOf("-1", "4294967296"))
        )
        for ((key, accepted, rejected) in cases) {
            val entry = d.entry("S", key)!!
            for (value in accepted) {
                assertEquals(
                    "$key should accept $value",
                    ValidationResult.Valid, entry.validate(value)
                )
            }
            for (value in rejected) {
                val result = entry.validate(value)
                assertTrue(
                    "$key should reject $value but was $result",
                    result is ValidationResult.Invalid
                )
                assertTrue(
                    (result as ValidationResult.Invalid).reason.contains(value)
                )
            }
        }
    }

    @Test
    fun `uint64 accepts the full unsigned range including values above the signed long max`() {
        val entry = CfgDocument.parse(integerSample).entry("S", "U64")!!
        assertEquals(ValidationResult.Valid, entry.validate("0"))
        assertEquals(ValidationResult.Valid, entry.validate("9223372036854775807"))
        // Above Long.MAX_VALUE: legal for UInt64, must not be rejected.
        assertEquals(ValidationResult.Valid, entry.validate("9223372036854775808"))
        assertEquals(ValidationResult.Valid, entry.validate("18446744073709551615"))
        assertInvalid(entry.validate("18446744073709551616"))
        assertInvalid(entry.validate("-1"))
        assertInvalid(entry.validate("1.5"))
        assertInvalid(entry.validate("lots"))
    }

    @Test
    fun `setValue writes choice values using the declared spelling`() {
        val d = doc()
        assertEquals(ValidationResult.Valid, d.setValue("S", "Backend", "FAST"))
        assertEquals("fast", d.entry("S", "Backend")!!.value)
        assertTrue(d.serialize().contains("Backend = fast"))

        assertEquals(ValidationResult.Valid, d.setValue("S", "Levels", "debug, info"))
        assertEquals("Debug, Info", d.entry("S", "Levels")!!.value)
        assertTrue(d.serialize().contains("Levels = Debug, Info"))
    }

    @Test
    fun `setValue on a missing entry is invalid with a readable reason`() {
        val result = doc().setValue("S", "NoSuchKey", "x")
        assertInvalid(result)
        assertTrue((result as ValidationResult.Invalid).reason.contains("NoSuchKey"))
    }
}
