package com.bepinex.android.ui.components

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bepinex.android.R
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.theme.BepInExTheme
import com.bepinex.cfgparser.CfgEntry
import com.bepinex.cfgparser.CfgValueKind
import java.io.File

private enum class ConfigEditorMode { FORM, RAW }

/**
 * Full-screen editor for BepInEx .cfg config files.
 *
 * Opens in form mode (ADR-0001): every entry renders with the control its
 * declared type calls for, generated from the metadata the file itself
 * carries. The raw text editor remains as an advanced mode — it warns
 * about values that violate the declared types instead of blocking, and
 * the unsaved-changes guard (including predictive back) applies to both
 * modes.
 */
@Composable
fun ConfigEditorDialog(
    configFile: File,
    onDismiss: () -> Unit,
    onSave: (File, String) -> Unit
) {
    val initialContent = remember(configFile) {
        runCatching { configFile.readText() }.getOrDefault("")
    }
    val session = remember(configFile) { ConfigFormSession(initialContent) }
    ConfigEditorScreen(
        title = configFile.name,
        session = session,
        onDismiss = onDismiss,
        onSave = { content -> onSave(configFile, content) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigEditorScreen(
    title: String,
    session: ConfigFormSession,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    // The session is a plain object; every mutation bumps this counter, and
    // reading it here makes the composition re-read entry values, drafts
    // and errors from the session whenever it changes.
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") revision
    fun mutated() {
        revision++
    }

    var mode by remember { mutableStateOf(ConfigEditorMode.FORM) }
    var rawText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var blockedReasons by remember { mutableStateOf<List<String>?>(null) }
    var rawWarnings by remember { mutableStateOf<List<String>?>(null) }
    var choicePickerIndex by remember { mutableStateOf<Int?>(null) }

    val hasChanges = !saved && when (mode) {
        ConfigEditorMode.FORM -> session.hasUnsavedChanges()
        // Judged against the file the editor was opened with — not the
        // current document, which already holds any form edit carried
        // over by the mode switch and would mask it.
        ConfigEditorMode.RAW -> session.isModified(rawText)
    }

    fun attemptSave() {
        when (mode) {
            ConfigEditorMode.FORM -> {
                val errors = session.commitDrafts()
                if (errors.isNotEmpty()) {
                    blockedReasons = errors.map { it.second }
                    mutated()
                } else {
                    saved = true
                    onSave(session.serialize())
                }
            }

            ConfigEditorMode.RAW -> {
                val problems = ConfigFormSession.validateText(rawText)
                if (problems.isNotEmpty()) {
                    rawWarnings = problems.map { it.second }
                } else {
                    saved = true
                    onSave(rawText)
                }
            }
        }
    }

    fun switchMode(target: ConfigEditorMode) {
        if (target == mode) return
        when (target) {
            ConfigEditorMode.RAW -> {
                // Carry the form state over, but never silently drop an
                // invalid draft: surface why and stay in the form.
                val errors = session.switchToRaw()
                if (errors.isNotEmpty()) {
                    blockedReasons = errors.map { it.second }
                    mutated()
                    return
                }
                rawText = session.serialize()
                mode = ConfigEditorMode.RAW
                mutated()
            }

            ConfigEditorMode.FORM -> {
                session.switchToForm(rawText)
                mode = ConfigEditorMode.FORM
                mutated()
            }
        }
    }

    // System back/gesture must honor the same unsaved-changes guard as the
    // toolbar back button (and opts into predictive back handling).
    BackHandler(enabled = hasChanges) { showDiscardDialog = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) showDiscardDialog = true
                        else onDismiss()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { attemptSave() }, enabled = hasChanges) {
                        Icon(
                            Icons.Filled.Save, stringResource(R.string.config_editor_save),
                            tint = if (hasChanges) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                SegmentedButton(
                    selected = mode == ConfigEditorMode.FORM,
                    onClick = { switchMode(ConfigEditorMode.FORM) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) }
                ) { Text(stringResource(R.string.config_editor_mode_form)) }
                SegmentedButton(
                    selected = mode == ConfigEditorMode.RAW,
                    onClick = { switchMode(ConfigEditorMode.RAW) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Filled.Code, null, Modifier.size(18.dp)) }
                ) { Text(stringResource(R.string.config_editor_mode_raw)) }
            }

            if (hasChanges) {
                Text(
                    stringResource(R.string.config_editor_unsaved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            when (mode) {
                ConfigEditorMode.FORM -> ConfigFormContent(
                    session = session,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onMutated = { mutated() },
                    onPickChoice = { choicePickerIndex = it }
                )

                ConfigEditorMode.RAW -> RawConfigContent(
                    text = rawText,
                    onTextChange = { rawText = it }
                )
            }
        }
    }

    // Single-choice picker (form mode)
    choicePickerIndex?.let { index ->
        val entry = session.entries[index]
        val options = entry.metadata?.acceptableValues.orEmpty()
        AlertDialog(
            onDismissRequest = { choicePickerIndex = null },
            title = { Text(entry.key) },
            text = {
                // Acceptable-value lists can run to dozens of entries;
                // without scrolling the dialog would overflow the screen.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = entry.value.equals(option, ignoreCase = true),
                                onClick = {
                                    session.applyValue(index, option)
                                    choicePickerIndex = null
                                    mutated()
                                }
                            )
                            Text(option, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choicePickerIndex = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }

    // Form save blocked: the values below violate their declared types.
    blockedReasons?.let { reasons ->
        AlertDialog(
            onDismissRequest = { blockedReasons = null },
            title = { Text(stringResource(R.string.config_editor_invalid_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.config_editor_invalid_msg))
                    Spacer(Modifier.height(8.dp))
                    reasons.forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { blockedReasons = null }) {
                    Text(stringResource(R.string.config_editor_ok))
                }
            }
        )
    }

    // Raw save with type violations: warn, but let the user save anyway.
    rawWarnings?.let { reasons ->
        AlertDialog(
            onDismissRequest = { rawWarnings = null },
            title = { Text(stringResource(R.string.config_editor_raw_warning_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.config_editor_raw_warning_msg))
                    Spacer(Modifier.height(8.dp))
                    reasons.forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    rawWarnings = null
                    saved = true
                    onSave(rawText)
                }) {
                    Text(stringResource(R.string.config_editor_save_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { rawWarnings = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }

    // Discard changes dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.config_editor_discard_title)) },
            text = { Text(stringResource(R.string.config_editor_discard_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onDismiss()
                }) {
                    Text(stringResource(R.string.confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.confirm_no))
                }
            }
        )
    }
}

@Composable
private fun ConfigFormContent(
    session: ConfigFormSession,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onMutated: () -> Unit,
    onPickChoice: (Int) -> Unit
) {
    val visible = session.search(searchQuery).toSet()
    val grouped = linkedMapOf<String, MutableList<Int>>()
    session.entries.forEachIndexed { index, entry ->
        if (index in visible) grouped.getOrPut(entry.section) { mutableListOf() }.add(index)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.config_editor_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Close, null)
                    }
                }
            },
            singleLine = true
        )

        if (grouped.isEmpty()) {
            Text(
                stringResource(R.string.config_editor_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            grouped.forEach { (section, indices) ->
                if (section.isNotEmpty()) {
                    item(key = "section-$section") {
                        Text(
                            section,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)
                        )
                    }
                }
                items(indices, key = { "entry-$it" }) { index ->
                    ConfigEntryItem(
                        entry = session.entries[index],
                        session = session,
                        index = index,
                        onMutated = onMutated,
                        onPickChoice = onPickChoice
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigEntryItem(
    entry: CfgEntry,
    session: ConfigFormSession,
    index: Int,
    onMutated: () -> Unit,
    onPickChoice: (Int) -> Unit
) {
    val metadata = entry.metadata
    val error = session.error(index)

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.key,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            metadata?.typeName?.let { type ->
                Text(
                    type,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!metadata?.description.isNullOrBlank()) {
            Text(
                metadata!!.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(Modifier.height(6.dp))

        when (entry.kind) {
            CfgValueKind.BOOLEAN -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = entry.value.equals("true", ignoreCase = true),
                        onCheckedChange = { checked ->
                            session.applyValue(index, if (checked) "true" else "false")
                            onMutated()
                        }
                    )
                }
            }

            CfgValueKind.SINGLE_CHOICE -> {
                OutlinedButton(
                    onClick = { onPickChoice(index) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(entry.value, Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
            }

            CfgValueKind.MULTI_CHOICE -> {
                val selected = entry.value.split(',').map { it.trim() }
                Column {
                    metadata?.acceptableValues.orEmpty().forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selected.any { it.equals(option, ignoreCase = true) },
                                onCheckedChange = { checked ->
                                    session.toggleMultiOption(index, option, checked)
                                    onMutated()
                                }
                            )
                            Text(option, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            CfgValueKind.INTEGER, CfgValueKind.DECIMAL -> {
                OutlinedTextField(
                    value = session.fieldText(index),
                    onValueChange = { session.setDraft(index, it); onMutated() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (entry.kind == CfgValueKind.INTEGER)
                            KeyboardType.Number else KeyboardType.Decimal
                    )
                )
            }

            CfgValueKind.STRING, CfgValueKind.UNTYPED -> {
                OutlinedTextField(
                    value = session.fieldText(index),
                    onValueChange = { session.setDraft(index, it); onMutated() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4,
                    isError = error != null
                )
            }
        }

        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        metadata?.defaultValue?.let { declaredDefault ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    stringResource(
                        R.string.config_editor_default_value,
                        declaredDefault.ifEmpty { stringResource(R.string.config_editor_empty_default) }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { session.reset(index); onMutated() }) {
                    Icon(
                        Icons.Filled.RestartAlt,
                        contentDescription = stringResource(R.string.config_editor_reset_default)
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RawConfigContent(
    text: String,
    onTextChange: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val transformation = remember(colorScheme) {
        SyntaxHighlightVisualTransformation(
            "cfg",
            SyntaxHighlightColors(
                property = colorScheme.primary,
                string = colorScheme.tertiary,
                number = colorScheme.secondary,
                boolean = colorScheme.secondary,
                nullLiteral = colorScheme.onSurfaceVariant,
                keyword = colorScheme.primary,
                function = colorScheme.tertiary,
                builtin = colorScheme.onSurfaceVariant,
                comment = colorScheme.onSurfaceVariant
            )
        )
    }

    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxSize().padding(top = 4.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp
        ),
        visualTransformation = transformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    )
}

// ---------------------------------------------------------------------------
// Previews — the agreed test seam for screens in this project. They pin the
// form's meaningful states: one entry per control kind (plus a
// metadata-less entry, which must stay visible), light/dark, and an
// enlarged system font.
// ---------------------------------------------------------------------------

private fun previewSession(): ConfigFormSession = ConfigFormSession(
    """
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
)

@Preview(name = "Config form - Light", showBackground = true, heightDp = 1100)
@Composable
private fun ConfigFormLightPreview() {
    BepInExTheme(themeMode = AppSettings.ThemeMode.LIGHT, dynamicColor = false) {
        ConfigEditorScreen(
            title = "BepInEx.cfg",
            session = previewSession(),
            onDismiss = {},
            onSave = {}
        )
    }
}

@Preview(
    name = "Config form - Dark",
    showBackground = true,
    heightDp = 1100,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ConfigFormDarkPreview() {
    BepInExTheme(themeMode = AppSettings.ThemeMode.DARK, dynamicColor = false) {
        ConfigEditorScreen(
            title = "BepInEx.cfg",
            session = previewSession(),
            onDismiss = {},
            onSave = {}
        )
    }
}

@Preview(name = "Config form - Enlarged font", showBackground = true, heightDp = 1400, fontScale = 2f)
@Composable
private fun ConfigFormLargeFontPreview() {
    BepInExTheme(themeMode = AppSettings.ThemeMode.LIGHT, dynamicColor = false) {
        ConfigEditorScreen(
            title = "BepInEx.cfg",
            session = previewSession(),
            onDismiss = {},
            onSave = {}
        )
    }
}
