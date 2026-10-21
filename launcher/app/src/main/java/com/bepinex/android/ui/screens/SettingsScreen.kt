package com.bepinex.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import com.bepinex.android.R
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.theme.BepInExTheme

/**
 * Settings screen with theme, language, and maintenance actions grouped into M3 cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /**
     * Package name of the currently selected game, or null when no game is
     * selected. Launcher Settings themselves are global and never need a
     * game; this only decides whether the per-game maintenance card is
     * shown (it has no target without a game). Maintenance moves to the
     * per-game settings screen in #35.
     */
    maintenancePackageName: String?,
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    dynamicColor: Boolean,
    animationDisabled: Boolean,
    floatingLogInGame: Boolean,
    screenDimInGame: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationDisabledChanged: (Boolean) -> Unit,
    onFloatingLogInGameChanged: (Boolean) -> Unit,
    onScreenDimInGameChanged: (Boolean) -> Unit,
    onClearBepInEx: () -> Unit,
    onClearDotnet: () -> Unit
) {
    var showThemeMenu by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 48.dp)
        ) {
            // Appearance Card
            item {
                SettingsSectionHeader(stringResource(R.string.settings_appearance_section))
                Spacer(Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Column {
                        // Theme item
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.settings_theme), fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text(when (themeMode) {
                                    AppSettings.ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                    AppSettings.ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                    AppSettings.ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                })
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable { showThemeMenu = true }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )

                        // Language item
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.settings_language), fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text(when (language) {
                                    AppSettings.Language.ENGLISH -> stringResource(R.string.lang_english)
                                    AppSettings.Language.CHINESE -> stringResource(R.string.lang_chinese)
                                    AppSettings.Language.KOREAN -> stringResource(R.string.lang_korean)
                                    AppSettings.Language.JAPANESE -> stringResource(R.string.lang_japanese)
                                    AppSettings.Language.SYSTEM -> stringResource(R.string.lang_system)
                                })
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable { showLangMenu = true }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )

                        // Dynamic Color (Material You)
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.settings_dynamic_color), fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = { Text(stringResource(R.string.settings_dynamic_color_desc)) },
                            leadingContent = {
                                Icon(Icons.Outlined.ColorLens, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = dynamicColor,
                                    onCheckedChange = onDynamicColorChanged
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )

                        // Disable Animations
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.settings_disable_animations), fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = { Text(stringResource(R.string.settings_disable_animations_desc)) },
                            leadingContent = {
                                Icon(Icons.Outlined.Animation, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = animationDisabled,
                                    onCheckedChange = onAnimationDisabledChanged
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
            }

            // In-Game Experience Card
            item {
                SettingsSectionHeader(stringResource(R.string.settings_ingame_section))
                Spacer(Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Column {
                        // Floating Log in Game
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.settings_floating_log), fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = { Text(stringResource(R.string.settings_floating_log_desc)) },
                            leadingContent = {
                                Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = floatingLogInGame,
                                    onCheckedChange = onFloatingLogInGameChanged
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )

                        // Screen Dim on Idle
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.settings_screen_dim), fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = { Text(stringResource(R.string.settings_screen_dim_desc)) },
                            leadingContent = {
                                Icon(Icons.Outlined.Brightness4, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = screenDimInGame,
                                    onCheckedChange = onScreenDimInGameChanged
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
            }

            // Maintenance Card — per-game actions, shown only while a game is
            // selected to target them (they move to the per-game settings
            // screen in #35). Global settings above work without any game.
            if (maintenancePackageName != null) {
            item {
                SettingsSectionHeader("Maintenance & Cache")
                Spacer(Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.settings_clear_bepinex), fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = { Text(stringResource(R.string.settings_clear_bepinex_desc)) },
                            leadingContent = {
                                Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                            },
                            trailingContent = {
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable { confirmAction = "bepinex" }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )

                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.settings_clear_dotnet), fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = { Text(stringResource(R.string.settings_clear_dotnet_desc)) },
                            leadingContent = {
                                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                            },
                            trailingContent = {
                                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier.clickable { confirmAction = "dotnet" }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )
                    }
                }
            }
            }

            // About Card
            item {
                SettingsSectionHeader("Application")
                Spacer(Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_about), fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = { Text("Licenses, architecture, and team credits") },
                        leadingContent = {
                            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { onNavigateToAbout() }
                    )
                }
            }
        }
    }

    // Theme selector dialog
    if (showThemeMenu) {
        AlertDialog(
            onDismissRequest = { showThemeMenu = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column(Modifier.padding(vertical = 4.dp)) {
                    ThemeOption(stringResource(R.string.theme_dark), AppSettings.ThemeMode.DARK, themeMode) {
                        onThemeChanged(it); showThemeMenu = false
                    }
                    ThemeOption(stringResource(R.string.theme_light), AppSettings.ThemeMode.LIGHT, themeMode) {
                        onThemeChanged(it); showThemeMenu = false
                    }
                    ThemeOption(stringResource(R.string.theme_system), AppSettings.ThemeMode.SYSTEM, themeMode) {
                        onThemeChanged(it); showThemeMenu = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeMenu = false }) { Text(stringResource(R.string.confirm_cancel)) }
            }
        )
    }

    // Language selector dialog
    if (showLangMenu) {
        AlertDialog(
            onDismissRequest = { showLangMenu = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.padding(vertical = 4.dp)) {
                    LangOption(stringResource(R.string.lang_english), AppSettings.Language.ENGLISH, language) {
                        onLanguageChanged(it); showLangMenu = false
                    }
                    LangOption(stringResource(R.string.lang_chinese), AppSettings.Language.CHINESE, language) {
                        onLanguageChanged(it); showLangMenu = false
                    }
                    LangOption(stringResource(R.string.lang_korean), AppSettings.Language.KOREAN, language) {
                        onLanguageChanged(it); showLangMenu = false
                    }
                    LangOption(stringResource(R.string.lang_japanese), AppSettings.Language.JAPANESE, language) {
                        onLanguageChanged(it); showLangMenu = false
                    }
                    LangOption(stringResource(R.string.lang_system), AppSettings.Language.SYSTEM, language) {
                        onLanguageChanged(it); showLangMenu = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLangMenu = false }) { Text(stringResource(R.string.confirm_cancel)) }
            }
        )
    }

    // Confirmation dialogs
    confirmAction?.let { action ->
        val (title, message, onConfirm) = when (action) {
            "bepinex" -> Triple(
                stringResource(R.string.confirm_clear_bepinex_title),
                stringResource(R.string.confirm_clear_bepinex_msg),
                { onClearBepInEx(); confirmAction = null }
            )
            "dotnet" -> Triple(
                stringResource(R.string.confirm_clear_dotnet_title),
                stringResource(R.string.confirm_clear_dotnet_msg),
                { onClearDotnet(); confirmAction = null }
            )
            else -> Triple("", "", { confirmAction = null })
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text(stringResource(R.string.confirm_no)) }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ThemeOption(
    label: String,
    mode: AppSettings.ThemeMode,
    current: AppSettings.ThemeMode,
    onSelect: (AppSettings.ThemeMode) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSelect(mode) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = mode == current, onClick = { onSelect(mode) })
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LangOption(
    label: String,
    lang: AppSettings.Language,
    current: AppSettings.Language,
    onSelect: (AppSettings.Language) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSelect(lang) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = lang == current, onClick = { onSelect(lang) })
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

// ---------------------------------------------------------------------------
// Previews — the agreed test seam for screens in this project (no UI-test
// framework). They pin the settings screen's meaningful states: with and
// without a selected game (maintenance card hidden), light/dark, and an
// enlarged system font.
// ---------------------------------------------------------------------------

@Composable
private fun SettingsScreenPreviewContent(
    maintenancePackageName: String?,
    themeMode: AppSettings.ThemeMode
) {
    BepInExTheme(themeMode = themeMode, dynamicColor = false) {
        SettingsScreen(
            maintenancePackageName = maintenancePackageName,
            themeMode = themeMode,
            language = AppSettings.Language.SYSTEM,
            dynamicColor = true,
            animationDisabled = false,
            floatingLogInGame = true,
            screenDimInGame = false,
            onNavigateBack = {},
            onNavigateToAbout = {},
            onThemeChanged = {},
            onLanguageChanged = {},
            onDynamicColorChanged = {},
            onAnimationDisabledChanged = {},
            onFloatingLogInGameChanged = {},
            onScreenDimInGameChanged = {},
            onClearBepInEx = {},
            onClearDotnet = {}
        )
    }
}

@Preview(name = "Settings - Light, game selected", showBackground = true, heightDp = 1000)
@Composable
private fun SettingsScreenLightPreview() {
    SettingsScreenPreviewContent(
        maintenancePackageName = "com.example.game",
        themeMode = AppSettings.ThemeMode.LIGHT
    )
}

@Preview(name = "Settings - Light, no game selected", showBackground = true, heightDp = 1000)
@Composable
private fun SettingsScreenNoGamePreview() {
    SettingsScreenPreviewContent(
        maintenancePackageName = null,
        themeMode = AppSettings.ThemeMode.LIGHT
    )
}

@Preview(
    name = "Settings - Dark, game selected",
    showBackground = true,
    heightDp = 1000,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun SettingsScreenDarkPreview() {
    SettingsScreenPreviewContent(
        maintenancePackageName = "com.example.game",
        themeMode = AppSettings.ThemeMode.DARK
    )
}

@Preview(name = "Settings - Enlarged font", showBackground = true, heightDp = 1200, fontScale = 2f)
@Composable
private fun SettingsScreenLargeFontPreview() {
    SettingsScreenPreviewContent(
        maintenancePackageName = "com.example.game",
        themeMode = AppSettings.ThemeMode.LIGHT
    )
}
