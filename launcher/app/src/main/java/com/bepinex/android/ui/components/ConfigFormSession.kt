package com.bepinex.android.ui.components

import com.bepinex.cfgparser.CfgDocument
import com.bepinex.cfgparser.CfgEntry
import com.bepinex.cfgparser.ValidationResult

/**
 * The edit session behind the cfg form editor (ADR-0001). Pure logic on
 * top of the :cfgparser document model — no Android or Compose types — so
 * the form's save contract is unit-testable:
 *
 * - Control edits (switch, choices) go through [CfgEntry.setValue]
 *   immediately, so an invalid value can never enter the document.
 * - Free-text edits are held as *drafts* first: a draft may be temporarily
 *   invalid (half-typed numbers), is validated live for display, and only
 *   reaches the document on [commitDrafts] when the form saves. An invalid
 *   draft blocks the save and leaves the document untouched.
 * - [serialize] therefore always differs from the text the session was
 *   opened with only in value lines the user actually changed.
 */
class ConfigFormSession(text: String) {

    /** The text the file held when the editor was opened. Never changes. */
    private val baseline: String = text

    private var document: CfgDocument = CfgDocument.parse(text)

    /** Uncommitted free-text edits, by entry index. */
    private val drafts = mutableMapOf<Int, String>()

    /** Reasons from the last failed control action (apply/reset), by entry index. */
    private val actionErrors = mutableMapOf<Int, String>()

    val entries: List<CfgEntry>
        get() = document.entries

    val sections: List<String>
        get() = document.sections

    /** The document as it would be written to disk now. */
    fun serialize(): String = document.serialize()

    /** Replaces the document with a fresh parse of [text] (raw-mode edits). */
    fun reload(text: String) {
        document = CfgDocument.parse(text)
        drafts.clear()
        actionErrors.clear()
    }

    // -- Drafts (free-text fields) -------------------------------------------

    fun draft(index: Int): String? = drafts[index]

    /** The text a field should show: the pending draft, or the entry's value. */
    fun fieldText(index: Int): String = drafts[index] ?: entries[index].value

    fun setDraft(index: Int, text: String) {
        drafts[index] = text
        actionErrors.remove(index)
    }

    /**
     * The reason [index]'s current input cannot be saved, or null: a live
     * draft validation error wins over a recorded action error.
     */
    fun error(index: Int): String? {
        val draft = drafts[index]
        if (draft != null) {
            val result = entries[index].validate(draft)
            if (result is ValidationResult.Invalid) return result.reason
        }
        return actionErrors[index]
    }

    // -- Control edits ---------------------------------------------------------

    /**
     * Applies a control-produced value (switch, single/multi choice) right
     * away. Valid values are written to the document in their declared
     * spelling; invalid ones are rejected and the reason is kept for
     * display via [error].
     */
    fun applyValue(index: Int, candidate: String): ValidationResult {
        val result = entries[index].setValue(candidate)
        if (result is ValidationResult.Valid) {
            drafts.remove(index)
            actionErrors.remove(index)
        } else if (result is ValidationResult.Invalid) {
            actionErrors[index] = result.reason
        }
        return result
    }

    /**
     * Toggles one option of a multi-choice entry. The resulting value is
     * ordered by the declared acceptable values, so toggling never
     * reshuffles what was already selected.
     */
    fun toggleMultiOption(index: Int, option: String, selected: Boolean): ValidationResult {
        val entry = entries[index]
        val current = entry.value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val toggled = if (selected) {
            if (current.any { it.equals(option, ignoreCase = true) }) current else current + option
        } else {
            current.filterNot { it.equals(option, ignoreCase = true) }
        }
        val declared = entry.metadata?.acceptableValues ?: emptyList()
        val ordered = declared.filter { d -> toggled.any { it.equals(d, ignoreCase = true) } } +
            toggled.filter { t -> declared.none { it.equals(t, ignoreCase = true) } }
        return applyValue(index, ordered.joinToString(", "))
    }

    /** Restores the entry's declared default, discarding any draft for it. */
    fun reset(index: Int): ValidationResult {
        val result = entries[index].resetToDefault()
        if (result is ValidationResult.Valid) {
            drafts.remove(index)
            actionErrors.remove(index)
        } else if (result is ValidationResult.Invalid) {
            actionErrors[index] = result.reason
        }
        return result
    }

    // -- Search ------------------------------------------------------------------

    /**
     * Indices of entries whose key or description contains [query]
     * (case-insensitive). A blank query matches everything. Entries are
     * never dropped for lacking metadata.
     */
    fun search(query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return entries.indices.toList()
        return entries.indices.filter { i ->
            val entry = entries[i]
            entry.key.contains(q, ignoreCase = true) ||
                entry.metadata?.description?.contains(q, ignoreCase = true) == true
        }
    }

    // -- Saving --------------------------------------------------------------------

    /** True when the document or a pending draft differs from the opened file. */
    fun hasUnsavedChanges(): Boolean =
        serialize() != baseline ||
            drafts.any { (index, draft) -> draft != entries[index].value }

    /** Drafts that cannot be saved, with the reason for each. */
    fun blockingErrors(): List<Pair<CfgEntry, String>> =
        drafts.mapNotNull { (index, draft) ->
            when (val result = entries[index].validate(draft)) {
                is ValidationResult.Invalid -> entries[index] to result.reason
                ValidationResult.Valid -> null
            }
        }

    fun canSave(): Boolean = blockingErrors().isEmpty()

    /**
     * Applies every draft that validates. Returns the drafts that did not
     * (same shape as [blockingErrors]); when it is empty, all drafts have
     * been committed and cleared.
     */
    fun commitDrafts(): List<Pair<CfgEntry, String>> {
        val errors = blockingErrors()
        if (errors.isNotEmpty()) return errors
        val pending = drafts.toList()
        drafts.clear()
        for ((index, draft) in pending) entries[index].setValue(draft)
        return emptyList()
    }

    companion object {
        /**
         * Validates every entry of an arbitrary cfg [text] (the raw editor's
         * content) and returns the entries whose current value violates
         * their declared schema, with reasons. Raw mode warns with this
         * list instead of blocking the save.
         */
        fun validateText(text: String): List<Pair<CfgEntry, String>> {
            val parsed = CfgDocument.parse(text)
            return parsed.entries.mapNotNull { entry ->
                when (val result = entry.validate(entry.value)) {
                    is ValidationResult.Invalid -> entry to result.reason
                    ValidationResult.Valid -> null
                }
            }
        }
    }
}
