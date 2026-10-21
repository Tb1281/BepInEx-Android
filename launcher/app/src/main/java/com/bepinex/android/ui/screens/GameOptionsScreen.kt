package com.bepinex.android.ui.screens

import android.content.res.Configuration
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.theme.BepInExTheme

/**
 * Game Options screen: the single home for everything specific to one game
 * (see GLOSSARY.md). It hosts the entry point for editing the game's
 * Framework Config and, grouped at the bottom in a danger zone, the per-game
 * maintenance actions with their confirmation dialogs.
 *
 * Before #35 those maintenance actions lived on the Launcher Settings
 * screen (targeting whichever game was selected) and the Framework Config
 * was reached from a shortcut button on the modpack list. Both routes are
 * gone; this screen is the only way in.
 */

/** A destructive maintenance action awaiting confirmation, if any. */
private enum class MaintenanceAction { BEPINEX, DOTNET }
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameOptionsScreen(
    gameLabel: String,
    packageName: String,
    onNavigateBack: () -> Unit,
    onEditFrameworkConfig: () -> Unit,
    onClearBepInEx: () -> Unit,
    onClearDotnet: () -> Unit
) {
    var confirmAction by remember { mutableStateOf<MaintenanceAction?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.game_options_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = gameLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
            // Which game these options belong to
            item {
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        text = gameLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Framework Config entry — editing itself happens in the
            // existing config editor (see BepInExNavHost).
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.game_options_framework_config), fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = { Text(stringResource(R.string.game_options_framework_config_desc)) },
                        leadingContent = {
                            Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { onEditFrameworkConfig() }
                    )
                }
            }

            // Danger zone — destructive per-game maintenance, grouped at
            // the bottom with the same confirmations and effects the
            // actions had on the Launcher Settings screen.
            item {
                Text(
                    text = stringResource(R.string.game_options_danger_zone),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp)
                )
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
                            modifier = Modifier.clickable { confirmAction = MaintenanceAction.BEPINEX }
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
                            modifier = Modifier.clickable { confirmAction = MaintenanceAction.DOTNET }
                        )
                    }
                }
            }
        }
    }

    // Confirmation dialogs — identical to the ones these actions had on
    // the Launcher Settings screen before the move.
    confirmAction?.let { action ->
        // Exhaustive over MaintenanceAction: every value that can reach
        // here has a real title/message, so no empty-dialog fallback.
        val (title, message, onConfirm) = when (action) {
            MaintenanceAction.BEPINEX -> Triple(
                stringResource(R.string.confirm_clear_bepinex_title),
                stringResource(R.string.confirm_clear_bepinex_msg),
                { onClearBepInEx(); confirmAction = null }
            )
            MaintenanceAction.DOTNET -> Triple(
                stringResource(R.string.confirm_clear_dotnet_title),
                stringResource(R.string.confirm_clear_dotnet_msg),
                { onClearDotnet(); confirmAction = null }
            )
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

// ---------------------------------------------------------------------------
// Previews — the agreed test seam for screens in this project (no UI-test
// framework). They pin the game options screen in light/dark and with an
// enlarged system font, following the SettingsScreen preview pattern.
// ---------------------------------------------------------------------------

@Composable
private fun GameOptionsScreenPreviewContent(themeMode: AppSettings.ThemeMode) {
    BepInExTheme(themeMode = themeMode, dynamicColor = false) {
        GameOptionsScreen(
            gameLabel = "The Tower",
            packageName = "com.example.game",
            onNavigateBack = {},
            onEditFrameworkConfig = {},
            onClearBepInEx = {},
            onClearDotnet = {}
        )
    }
}

@Preview(name = "Game Options - Light", showBackground = true, heightDp = 700)
@Composable
private fun GameOptionsScreenLightPreview() {
    GameOptionsScreenPreviewContent(themeMode = AppSettings.ThemeMode.LIGHT)
}

@Preview(
    name = "Game Options - Dark",
    showBackground = true,
    heightDp = 700,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun GameOptionsScreenDarkPreview() {
    GameOptionsScreenPreviewContent(themeMode = AppSettings.ThemeMode.DARK)
}

@Preview(name = "Game Options - Enlarged font", showBackground = true, heightDp = 900, fontScale = 2f)
@Composable
private fun GameOptionsScreenLargeFontPreview() {
    GameOptionsScreenPreviewContent(themeMode = AppSettings.ThemeMode.LIGHT)
}
