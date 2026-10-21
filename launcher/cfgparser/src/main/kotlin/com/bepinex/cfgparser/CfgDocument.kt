package com.bepinex.cfgparser

/**
 * The kind of value a cfg entry holds, derived from the schema BepInEx writes
 * into the entry's comment header (see ADR-0001). [UNTYPED] means the entry
 * has no header metadata and is treated as a plain string.
 */
enum class CfgValueKind {
    BOOLEAN,
    INTEGER,
    DECIMAL,
    STRING,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    UNTYPED
}

/**
 * The schema of one cfg entry, extracted from its comment header.
 *
 * @param description the `##` lines above the entry, joined with newlines
 * @param typeName the raw `# Setting type:` value (e.g. "Boolean", "LogLevel"), or null if absent
 * @param defaultValue the raw `# Default value:` value; null if the header declares none.
 *   An explicitly empty default is the empty string, not null.
 * @param acceptableValues the `# Acceptable values:` list, empty if undeclared
 * @param allowsMultipleValues whether the header carries the "multiple values can be set" note
 */
data class CfgMetadata(
    val description: String,
    val typeName: String?,
    val defaultValue: String?,
    val acceptableValues: List<String>,
    val allowsMultipleValues: Boolean
)

/**
 * One `Key = Value` entry of a cfg file, with the schema from its comment
 * header. Instances belong to the [CfgDocument] that parsed them; the
 * document rewrites the entry's line when its value changes.
 */
class CfgEntry internal constructor(
    val section: String,
    val key: String,
    initialValue: String,
    val metadata: CfgMetadata?,
    internal val lineIndex: Int,
    internal val valueStart: Int
) {
    /** The entry's current value, as written in the file (trimmed). */
    var value: String = initialValue
        internal set

    /** The value kind derived from [metadata]. */
    val kind: CfgValueKind
        get() = kindOf(metadata)

    internal lateinit var owner: CfgDocument

    /**
     * Checks [candidate] against this entry's declared type and acceptable
     * values. Entries with no declared schema accept any value.
     */
    fun validate(candidate: String): ValidationResult {
        val m = metadata
        return when (kind) {
            CfgValueKind.BOOLEAN ->
                if (candidate.equals("true", ignoreCase = true) ||
                    candidate.equals("false", ignoreCase = true)
                ) ValidationResult.Valid
                else ValidationResult.Invalid(
                    "\"$candidate\" is not valid for $key: expected true or false."
                )

            CfgValueKind.INTEGER -> {
                if (m?.typeName.equals("UInt64", ignoreCase = true)) {
                    // UInt64 needs an unsigned parse: a signed Long cannot
                    // represent values above Long.MAX_VALUE, which are legal
                    // here and must not be rejected as "not a whole number".
                    val parsed = candidate.toULongOrNull()
                    when {
                        parsed == null -> ValidationResult.Invalid(
                            "\"$candidate\" is not valid for $key: expected a whole number."
                        )
                        parsed !in ULong.MIN_VALUE..ULong.MAX_VALUE -> ValidationResult.Invalid(
                            "\"$candidate\" is not valid for $key: outside the range of ${m?.typeName}."
                        )
                        else -> ValidationResult.Valid
                    }
                } else {
                    val parsed = candidate.toLongOrNull()
                    when {
                        parsed == null -> ValidationResult.Invalid(
                            "\"$candidate\" is not valid for $key: expected a whole number."
                        )
                        !inIntegerRange(parsed, m?.typeName) -> ValidationResult.Invalid(
                            "\"$candidate\" is not valid for $key: outside the range of ${m?.typeName}."
                        )
                        else -> ValidationResult.Valid
                    }
                }
            }

            CfgValueKind.DECIMAL ->
                if (candidate.toDoubleOrNull()?.isFinite() == true) ValidationResult.Valid
                else ValidationResult.Invalid(
                    "\"$candidate\" is not valid for $key: expected a number."
                )

            CfgValueKind.SINGLE_CHOICE -> {
                val options = m!!.acceptableValues
                if (options.any { it.equals(candidate, ignoreCase = true) }) ValidationResult.Valid
                else ValidationResult.Invalid(
                    "\"$candidate\" is not valid for $key: choose one of ${options.joinToString(", ")}."
                )
            }

            CfgValueKind.MULTI_CHOICE -> {
                val options = m!!.acceptableValues
                val tokens = candidate.split(',').map { it.trim() }
                val bad = tokens.firstOrNull { token ->
                    token.isEmpty() || options.none { it.equals(token, ignoreCase = true) }
                }
                when {
                    candidate.isBlank() || bad != null -> ValidationResult.Invalid(
                        "\"${bad ?: candidate}\" is not valid for $key: " +
                            "combine values from ${options.joinToString(", ")}, separated by commas."
                    )
                    else -> ValidationResult.Valid
                }
            }

            CfgValueKind.STRING, CfgValueKind.UNTYPED -> ValidationResult.Valid
        }
    }

    /**
     * Validates [candidate] and, if valid, makes it this entry's value.
     * The owning document's line for this entry is rewritten in place:
     * everything from the value to the end of the line is replaced, the
     * part before the value is untouched. For choice entries the value
     * written is the declared spelling of the matched option(s), even
     * when [candidate] matched only case-insensitively.
     * On [ValidationResult.Invalid] nothing is modified.
     */
    fun setValue(candidate: String): ValidationResult {
        val result = validate(candidate)
        if (result is ValidationResult.Valid) owner.applyValue(this, canonicalSpelling(candidate))
        return result
    }

    /**
     * The spelling [candidate] is written with: for choice entries, each
     * matched option's declared spelling from the acceptable values;
     * for every other kind, [candidate] itself.
     */
    private fun canonicalSpelling(candidate: String): String {
        val m = metadata ?: return candidate
        return when (kind) {
            CfgValueKind.SINGLE_CHOICE ->
                m.acceptableValues.firstOrNull { it.equals(candidate, ignoreCase = true) }
                    ?: candidate
            CfgValueKind.MULTI_CHOICE ->
                candidate.split(',').joinToString(", ") { token ->
                    val trimmed = token.trim()
                    m.acceptableValues.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                        ?: trimmed
                }
            else -> candidate
        }
    }

    /**
     * Restores the default value declared in this entry's header metadata.
     * Entries without a declared default cannot be reset and return
     * [ValidationResult.Invalid] without changing anything.
     */
    fun resetToDefault(): ValidationResult {
        val declared = metadata?.defaultValue
            ?: return ValidationResult.Invalid(
                "$key has no default value recorded in the file, so it cannot be reset."
            )
        return setValue(declared)
    }

    private fun inIntegerRange(value: Long, typeName: String?): Boolean = when (typeName?.lowercase()) {
        "byte" -> value in 0..255
        "sbyte" -> value in -128..127
        "int16" -> value in -32768..32767
        "uint16" -> value in 0..65535
        "int32" -> value in Int.MIN_VALUE..Int.MAX_VALUE
        "uint32" -> value in 0..4294967295L
        else -> true
    }

    internal companion object {
        private val INTEGER_TYPES = setOf(
            "byte", "sbyte", "int16", "uint16", "int32", "uint32", "int64", "uint64"
        )
        private val DECIMAL_TYPES = setOf("single", "double", "decimal")

        fun kindOf(metadata: CfgMetadata?): CfgValueKind {
            val typeName = metadata?.typeName ?: return CfgValueKind.UNTYPED
            return when {
                typeName.equals("Boolean", ignoreCase = true) -> CfgValueKind.BOOLEAN
                typeName.lowercase() in INTEGER_TYPES -> CfgValueKind.INTEGER
                typeName.lowercase() in DECIMAL_TYPES -> CfgValueKind.DECIMAL
                metadata.acceptableValues.isNotEmpty() ->
                    if (metadata.allowsMultipleValues) CfgValueKind.MULTI_CHOICE
                    else CfgValueKind.SINGLE_CHOICE
                else -> CfgValueKind.STRING
            }
        }
    }
}

/**
 * A parsed BepInEx cfg file.
 *
 * The document keeps every original line, so [serialize] reproduces the
 * input byte for byte when nothing was changed, and — once values are
 * edited through the document — differs only in the changed value lines
 * (comments, blank lines, ordering, and unknown entries are preserved).
 */
class CfgDocument private constructor(
    private val lines: MutableList<RawLine>,
    val sections: List<String>,
    val entries: List<CfgEntry>
) {
    /** Looks up an entry by section and key, or null if there is none. */
    fun entry(section: String, key: String): CfgEntry? =
        entries.firstOrNull { it.section == section && it.key == key }

    /**
     * Validates [candidate] for the named entry and applies it if valid.
     * A missing entry is [ValidationResult.Invalid], never an exception.
     */
    fun setValue(section: String, key: String, candidate: String): ValidationResult {
        val target = entry(section, key)
            ?: return ValidationResult.Invalid("No setting named \"$key\" in section \"$section\".")
        return target.setValue(candidate)
    }

    /** Restores the named entry to the default declared in its header metadata. */
    fun resetToDefault(section: String, key: String): ValidationResult {
        val target = entry(section, key)
            ?: return ValidationResult.Invalid("No setting named \"$key\" in section \"$section\".")
        return target.resetToDefault()
    }

    /**
     * Rewrites [entry]'s line in place: the text before the value is kept
     * as-is, and the value together with everything after it on the line
     * is replaced by [candidate].
     */
    internal fun applyValue(entry: CfgEntry, candidate: String) {
        val line = lines[entry.lineIndex]
        line.text = line.text.substring(0, entry.valueStart) + candidate
        entry.value = candidate
    }

    /** Serialises the document back to cfg text. */
    fun serialize(): String = buildString {
        for (line in lines) {
            append(line.text)
            append(line.terminator)
        }
    }

    internal class RawLine(var text: String, val terminator: String)

    companion object {
        /** Parses cfg [text] into a document. Never fails: malformed lines are preserved as-is. */
        fun parse(text: String): CfgDocument {
            val rawLines = splitLines(text)
            val sections = mutableListOf<String>()
            val entries = mutableListOf<CfgEntry>()
            var currentSection = ""
            var pendingComments = mutableListOf<String>()

            rawLines.forEachIndexed { index, line ->
                val trimmed = line.text.trim()
                when {
                    trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length >= 2 -> {
                        currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                        if (currentSection !in sections) sections.add(currentSection)
                        pendingComments = mutableListOf()
                    }
                    trimmed.startsWith("#") -> pendingComments.add(trimmed)
                    trimmed.isEmpty() -> Unit // a header block may span blank-line gaps in hand edits
                    else -> {
                        val eq = line.text.indexOf('=')
                        val key = if (eq > 0) line.text.substring(0, eq).trim() else ""
                        if (key.isNotEmpty()) {
                            var valueStart = eq + 1
                            while (valueStart < line.text.length &&
                                (line.text[valueStart] == ' ' || line.text[valueStart] == '\t')
                            ) valueStart++
                            val value = line.text.substring(valueStart).trim()
                            entries.add(
                                CfgEntry(
                                    section = currentSection,
                                    key = key,
                                    initialValue = value,
                                    metadata = metadataOf(pendingComments),
                                    lineIndex = index,
                                    valueStart = valueStart
                                )
                            )
                        }
                        pendingComments = mutableListOf()
                    }
                }
            }
            val document = CfgDocument(rawLines, sections, entries)
            entries.forEach { it.owner = document }
            return document
        }

        private fun splitLines(text: String): MutableList<RawLine> {
            val result = mutableListOf<RawLine>()
            var start = 0
            for (i in text.indices) {
                if (text[i] == '\n') {
                    var contentEnd = i
                    var terminator = "\n"
                    if (i > start && text[i - 1] == '\r') {
                        contentEnd = i - 1
                        terminator = "\r\n"
                    }
                    result.add(RawLine(text.substring(start, contentEnd), terminator))
                    start = i + 1
                }
            }
            if (start < text.length) {
                result.add(RawLine(text.substring(start), ""))
            } else if (text.isEmpty()) {
                // empty document: no lines at all
            }
            return result
        }

        private fun metadataOf(comments: List<String>): CfgMetadata? {
            val isMetadata = comments.any {
                it.startsWith("##") ||
                    it.startsWith("# Setting type:") ||
                    it.startsWith("# Default value:") ||
                    it.startsWith("# Acceptable values:")
            }
            if (!isMetadata) return null

            val description = comments
                .filter { it.startsWith("##") }
                .joinToString("\n") { it.removePrefix("##").removePrefix(" ") }
                .trim()
            fun field(prefix: String): String? =
                comments.firstOrNull { it.startsWith(prefix) }
                    ?.removePrefix(prefix)
                    ?.removePrefix(" ")
            val acceptable = field("# Acceptable values:")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            return CfgMetadata(
                description = description,
                typeName = field("# Setting type:")?.trim()?.ifEmpty { null },
                defaultValue = field("# Default value:")?.trim(),
                acceptableValues = acceptable,
                allowsMultipleValues = comments.any { it.startsWith("# Multiple values can be set") }
            )
        }
    }
}
